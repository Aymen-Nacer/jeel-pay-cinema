# Jeel Pay Cinema — Take-Home Assessment

A full-stack Spring Boot cinema booking application with Moyasar payments, Resend email, and real-database integration tests.

---

## Quick Start

### Prerequisites

- Docker & Docker Compose
- Java 21 (for running tests)
- Gradle (wrapper included)

### Environment Variables

Create a `.env` file in the project root or export these before running:

```bash
MOYASAR_PUBLIC_KEY=pk_test_...
MOYASAR_SECRET_KEY=sk_test_...
RESEND_API_KEY=re_...
# Optional: shared secret echoed back by Moyasar webhooks (secret_token).
# If unset, webhook signature verification is disabled (fine for local/dev).
MOYASAR_WEBHOOK_SECRET=
```

### Run with Docker Compose

```bash
docker compose up --build
```

The app starts at **[http://localhost:8080](http://localhost:8080)**.

### Run Locally (with Docker MySQL)

```bash
# Start only the database
docker compose up db -d

# Run the app
./gradlew bootRun \
  -Dapp.moyasar.secret-key=sk_test_... \
  -Dapp.moyasar.public-key=pk_test_... \
  -Dapp.resend.api-key=re_...
```

### Run Tests

Tests use **Testcontainers** (real MySQL spun per suite) and **WireMock** (Moyasar + Resend stubbed). Docker must be running for the integration tests.

```bash
./gradlew test
```

> **Spring Boot 4 note:** `TestRestTemplate` moved to `org.springframework.boot.resttestclient` and is no longer pulled in transitively, so the test classpath explicitly depends on `spring-boot-restclient` + `spring-boot-resttestclient`, and the integration base class is annotated `@AutoConfigureTestRestTemplate`.

---

## Seeded Credentials

| Role  | Email                                         | Password |
| ----- | --------------------------------------------- | -------- |
| ADMIN | [admin@jeelpay.com](mailto:admin@jeelpay.com) | admin    |

Register a USER at `/register`.

---

## Database Migrations

Schema is managed entirely by Flyway. Four migrations cover a fresh install:

| Migration | Purpose |
|-----------|---------|
| `V1__init_schema.sql` | Complete schema: all tables in final form (UUID bookings, `booking_seats` junction, `showtime_seats` with `seat_id` FK, `bookings.updated_at` for refund reconciliation) |
| `V2__spring_session.sql` | Spring Session JDBC tables (`SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES`) |
| `V3__seed.sql` | Admin account, 10 TMDB movies, 2 halls, 6 showtimes with future dates |
| `V4__seats.sql` | Stored procedure `generate_seats_for_hall` + generates all physical seats as STANDARD + populates `showtime_seats` |

The stored procedure `generate_seats_for_hall(hall_id)` is idempotent: it wipes and regenerates seats for the given hall from its `total_rows` and `seats_per_row` dimensions.

---

## Architecture & Design Decisions

### Concurrency: Seat Race

Two concurrent requests to book the same seat are serialised by `SELECT … FOR UPDATE NOWAIT` on the `showtime_seats` row inside a `@Transactional` method. The first transaction holds a row-level lock; the second fails fast with a lock timeout and receives `SeatUnavailableException`. This approach:

- Is deterministic (no lost updates, no double booking)
- Requires no application-level locking or Redis
- Uses `NOWAIT` so the failing request gets an immediate error rather than queuing

The `version` column on `showtime_seats` is also maintained (incremented on each hold/confirm/release) to support future optimistic-locking readers.

### Transaction Boundaries

| Action                                     | Inside transaction | Outside transaction |
| ------------------------------------------ | ------------------ | ------------------- |
| Lock seat + create PENDING booking         | ✅                  |                     |
| Call Moyasar (create payment)              |                    | ✅ no DB conn held   |
| Confirm booking (DB update + seat confirm) | ✅ atomic           |                     |
| Call Moyasar (verify payment)              |                    | ✅                   |
| Admin cancel DB update + seat release      | ✅                  |                     |
| Call Moyasar (refund)                      |                    | ✅                   |

External HTTP calls (Moyasar, Resend) are deliberately placed **outside** transactions. Holding a DB connection while waiting for an external service would exhaust the connection pool and create deadlock risk.

### Notifications as Side Effects

All emails are published as Spring `ApplicationEvent`s with `@TransactionalEventListener(phase = AFTER_COMMIT)`. This means:

1. Emails fire only after the DB transaction durably commits — no risk of sending a "confirmed" email for a booking that later rolls back.
2. Email failures (Resend down, network error) are swallowed in the listener and logged; they **never** roll back or block the underlying action.
3. A registered user is still registered even if the welcome email bounces.

### Payment Confirmation

Payment is considered paid only after Moyasar returns `status: paid` on a direct API call to `GET /payments/{id}`. The browser redirect carries the `id` (payment ID) and `status` query parameters, but these are **not trusted** — we always re-verify with Moyasar before updating the booking. The same is true for the webhook: its body is only used to identify which payment to re-verify.

Amount reconciliation is also performed: `booking.totalAmount × 100` (SAR → halala) must match `payment.amount`. Money is handled exclusively with `BigDecimal`/integer halala — never floating point.

### Two payment-confirmation paths (Moyasar webhook — stretch)

1. **Browser redirect** — `GET /bookings/{id}/payment/callback` after the user pays.
2. **Webhook** — `POST /webhooks/moyasar`, a server-to-server call so a booking still confirms even if the user's browser never returns.

Both paths funnel through the same idempotent `confirmIfPending` DB call (`UPDATE … WHERE status = 'PENDING'`), so they can race or both fire without double-confirming, double-emailing, or double-booking. The webhook is CSRF-exempt and is instead authenticated by the shared `secret_token` (`MOYASAR_WEBHOOK_SECRET`) when configured.

### Idempotency

| Case                                                    | Guard                                                                                        |
| ------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| Duplicate confirmation (browser redirect + webhook)     | `UPDATE … WHERE status = 'PENDING'` returns 0 rows if already CONFIRMED; `UNIQUE` constraint on `moyasar_payment_id` as hard DB backstop |
| Duplicate admin cancel                                  | `UPDATE … WHERE status = 'CONFIRMED'` returns 0 rows if already CANCELLED                    |
| Reminder job                                            | `reminder_sent` flag set **before** sending the email; duplicate runs skip already-reminded bookings |
| Refund reconciliation cron                              | `UPDATE … WHERE status = 'REFUND_PENDING'` — only repairs genuinely stuck rows               |

### Seat Hold Expiry

A PENDING booking holds a seat for `app.booking.seat-hold-minutes` (default 15 minutes). A `@Scheduled(fixedDelay = 5 min)` job finds expired PENDING bookings and releases them. If the user never completes payment, the seat reverts to AVAILABLE within 20 minutes at most.

### Admin Cancellation (crash-safe)

The cancel flow uses three steps to be crash-safe:

1. **DB (fast):** atomically CONFIRMED → REFUND_PENDING (only one caller wins this CAS).
2. **Network:** call Moyasar to refund (no DB transaction open during the HTTP call).
3. **DB:** on success REFUND_PENDING → CANCELLED + release seats; on Moyasar failure revert to CONFIRMED.

A `@Scheduled` reconciliation job finds bookings stuck in `REFUND_PENDING` (crash between steps 2 and 3) and resolves them using the actual Moyasar payment state.

### Resilience

- `RestClient` (synchronous HTTP) is used for both Moyasar and Resend, each with explicit **connect/read timeouts** (Moyasar 5s/15s, Resend 5s/10s).
- Email failures are swallowed (never propagate to the user flow).
- Moyasar failures during payment initiation cause the PENDING booking to be released immediately.
- Moyasar refund failures propagate to the admin (booking stays CONFIRMED so money is safe; admin can retry).

### Movie Data

10 movies are pre-seeded via Flyway (`V3__seed.sql`) with full TMDB data (fetched once and committed). TMDB is never called at runtime. Showtimes are seeded to July–August 2026.

---

## Assumptions (where the spec was silent)

1. **Seat hold duration**: 15 minutes. After that, the seat is released automatically.
2. **Payment URL model**: Moyasar `source.transaction_url` is the hosted payment page URL. The callback returns to `/bookings/{id}/payment/callback?id={payment_id}&status={status}`.
3. **PENDING bookings in "My Bookings"**: Shown with a PENDING badge; the hold will expire and seat be released by the background job if the user abandons payment.
4. **Admin cannot self-register**: Admin account is seeded via Flyway only (as per spec).
5. **Reminder job timezone**: `Asia/Riyadh` (UTC+3). Showtimes are stored in UTC; the query uses `CONVERT_TZ` to compare the Riyadh local date.
6. **`reminder_sent` is set before sending**: Makes the job idempotent — if the JVM crashes mid-run, the app won't re-send on restart. A missed reminder is preferable to a duplicate.
7. **No user-facing cancellation**: Only admins can cancel (as per spec).
8. **Password policy**: Minimum 8 characters (spec says "reasonable rules").
9. **Session store**: Spring Session JDBC — sessions survive app restarts and are shared across instances.
10. **Late payment after hold expiry**: If a payment arrives after the cron has cancelled the booking, `handleWebhookPayment` auto-refunds the charge and marks the booking `REFUNDED_LATE_PAYMENT` so operators can audit it. The `confirmPayment` browser-redirect path also detects this and returns an error explaining a refund will be issued.
11. **Webhook verification**: The Moyasar webhook is authenticated by the `secret_token` it echoes back (`MOYASAR_WEBHOOK_SECRET`). When the secret is blank, verification is skipped for local/dev convenience. A booking is still re-verified against Moyasar's API regardless, so an unauthenticated webhook can never confirm an unpaid booking.
12. **No past-showtime guard**: Showtimes are seeded with future dates; there is no server-side check preventing booking past showtimes, which is acceptable for this assessment.
13. **Scheduled jobs (Reminders / Expired Holds)**: The `releaseExpiredHolds` and `reconcilePendingRefunds` cron jobs are implemented and tested at the query level (`ReminderTest` covers the Riyadh-timezone query and `reminder_sent` idempotency). Full time-manipulation integration tests (e.g. advancing the clock to force expiry within a test) were deferred to focus testing effort on the financially critical payment and concurrency paths.

---

## Known Limitations & Deferred Edge Cases

The test suite deliberately covers the **critical path**: Happy Path, Concurrency (Race Condition), and Idempotency. The table below documents what is known, why it was prioritised the way it was, and what the proposed improvement would be.

| Area | Coverage | Notes |
|------|----------|-------|
| **Booking entry point** (HTTP end-to-end) | ✅ `BookingFlowTest.httpEndToEnd_postBook_*` | Registers a user, logs in via `TestRestTemplate`, POSTs to `/showtimes/{id}/book`, and asserts `PENDING` booking + `HELD` seat in the database |
| **Payment confirmation** | ✅ `BookingFlowTest.successfulPayment_*` | Asserts `Booking.status == CONFIRMED` **and** `ShowtimeSeat.status == BOOKED` and verifies Resend received the confirmation email |
| **Seat race condition** | ✅ `SeatConcurrencyTest` | Two threads race for the same seat; exactly one wins — proven via `SELECT … FOR UPDATE NOWAIT` |
| **Idempotent confirmation** | ✅ `BookingFlowTest.duplicateConfirmation_*` | Webhook and browser redirect can both fire; only the first `UPDATE … WHERE status = 'PENDING'` succeeds |
| **Admin cancellation + seat release** | ✅ `BookingFlowTest.adminCancelConfirmedBooking_*` | Asserts `CANCELLED` booking and `AVAILABLE` seat |
| **Webhook confirmation path** | ✅ `WebhookConfirmationTest` | Server-to-server confirm without a browser |
| **Hold expiry (cron)** | ⚠️ Query-level only | The cron SQL is tested via `ReminderTest`; driving actual time expiry in a test was deferred in favour of financial-path coverage |
| **Refund reconciliation (cron)** | ⚠️ Not integration-tested | `reconcilePendingRefunds` logic is present and crash-safe by design; a dedicated integration test with a simulated Moyasar outage is a suggested future improvement |
| **Past-showtime guard** | ❌ Not implemented | Showtimes are seeded with future dates; a server-side check is a low-risk future addition |
| **Outbox pattern for guaranteed email** | ❌ Not implemented | The current `@Async + @TransactionalEventListener` approach is safe (emails fire only after commit) but not durable across a JVM crash post-commit. An outbox table would be the production hardening step |
| **Rate limiting on `/register` and `/login`** | ❌ Not implemented | Suggested improvement; out of scope for this assessment |

---

## Stretch Goals Delivered

- **Moyasar webhook** — `POST /webhooks/moyasar` as a second, idempotent confirmation path.
- **Multi-seat booking** — reserve several seats in one atomic booking (all hold or none do); a `booking_seats` junction table links one booking to many seats.

## What I'd Improve With More Time

1. **Late-payment reconciliation**: If a `paid` webhook arrives after the hold expired and the seat was re-sold, today it is auto-refunded and flagged. With more time I'd add operator alerting.
2. **Outbox pattern**: For guaranteed email delivery, use a transactional outbox table and a separate dispatcher instead of relying on `AFTER_COMMIT` events.
3. **Rate limiting**: On `/register` and `/login` to prevent brute-force.
4. **Admin movie/showtime CRUD**: Currently seeded via Flyway only.
5. **Metrics & observability**: Micrometer + Prometheus for payment success rates, seat availability, etc.
6. **Past-showtime guard**: Prevent booking showtimes whose `start_time` is in the past.
