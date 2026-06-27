package com.jeelpay.cinema.service;

import com.jeelpay.cinema.config.AppProperties;
import com.jeelpay.cinema.domain.*;
import com.jeelpay.cinema.event.BookingCancelledEvent;
import com.jeelpay.cinema.event.BookingConfirmedEvent;
import com.jeelpay.cinema.event.BookingRefundedLateEvent;
import com.jeelpay.cinema.integration.moyasar.MoyasarClient;
import com.jeelpay.cinema.integration.moyasar.MoyasarException;
import com.jeelpay.cinema.integration.moyasar.MoyasarPaymentResponse;
import com.jeelpay.cinema.repository.BookingRepository;
import com.jeelpay.cinema.repository.ShowtimeRepository;
import com.jeelpay.cinema.repository.ShowtimeSeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookingRepository;
    private final ShowtimeRepository showtimeRepository;
    private final ShowtimeSeatRepository seatRepository;
    private final MoyasarClient moyasarClient;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final AppProperties appProperties;

    public BookingService(BookingRepository bookingRepository,
                          ShowtimeRepository showtimeRepository,
                          ShowtimeSeatRepository seatRepository,
                          MoyasarClient moyasarClient,
                          ApplicationEventPublisher eventPublisher,
                          TransactionTemplate transactionTemplate,
                          AppProperties appProperties) {
        this.bookingRepository = bookingRepository;
        this.showtimeRepository = showtimeRepository;
        this.seatRepository = seatRepository;
        this.moyasarClient = moyasarClient;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
        this.appProperties = appProperties;
    }

    // ── Phase 1: Tx1 ─────────────────────────────────────────────────────────
    // Lock all requested seat rows with SELECT FOR UPDATE NOWAIT (fail-fast),
    // generate a UUID booking id, and persist the PENDING booking.
    // Transaction commits here — DB connection is released before the network call.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Atomically acquire pessimistic locks on all requested seats (FOR UPDATE NOWAIT)
     * and insert a PENDING booking that covers all of them.
     *
     * @param seatNumbers list of seat labels to book, e.g. ["A1", "A2"]
     */
    @Transactional
    public Booking createPendingBooking(Long showtimeId, List<String> seatNumbers, Long userId) {
        if (seatNumbers == null || seatNumbers.isEmpty()) {
            throw new IllegalArgumentException("At least one seat must be selected");
        }

        Showtime showtime = showtimeRepository.findByIdBasic(showtimeId)
                .orElseThrow(() -> new IllegalArgumentException("Showtime not found: " + showtimeId));

        List<ShowtimeSeat> seats;
        try {
            seats = seatRepository.lockMultipleForUpdate(showtimeId, seatNumbers);
        } catch (DataAccessException ex) {
            throw new SeatUnavailableException("One or more seats are currently being booked — please try again");
        }

        if (seats.size() != seatNumbers.size()) {
            List<String> found = seats.stream().map(ShowtimeSeat::getSeatNumber).collect(Collectors.toList());
            List<String> missing = seatNumbers.stream().filter(n -> !found.contains(n)).collect(Collectors.toList());
            throw new IllegalArgumentException("Seats not found: " + missing);
        }

        for (ShowtimeSeat seat : seats) {
            if (seat.getStatus() != SeatStatus.AVAILABLE) {
                throw new SeatUnavailableException("Seat " + seat.getSeatNumber() + " is no longer available");
            }
        }

        BigDecimal total = showtime.getPrice().multiply(BigDecimal.valueOf(seats.size()));

        Booking booking = new Booking();
        booking.setUserId(userId);
        booking.setShowtimeId(showtimeId);
        booking.setSeatIds(seats.stream().map(ShowtimeSeat::getSeatId).collect(Collectors.toList()));
        booking.setStatus(BookingStatus.PENDING);
        booking.setTotalAmount(total);

        booking = bookingRepository.save(booking);

        List<Long> showTimeSeatIds = seats.stream().map(ShowtimeSeat::getId).collect(Collectors.toList());
        seatRepository.holdSeats(showTimeSeatIds, booking.getId());

        String label = seats.stream().map(ShowtimeSeat::getSeatNumber).collect(Collectors.joining(", "));
        booking.setSeatNumber(label);
        return booking;
        // Tx1 commits here — seats are HELD, booking is PENDING.
    }

    // ── Phase 2: Network call (no transaction) ────────────────────────────────
    // Call Moyasar outside any transaction. No DB connection is held during the
    // HTTP round-trip.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Create a Moyasar payment for the given booking, then immediately persist the
     * returned Moyasar payment ID so the cron job can query Moyasar by it (Tx1.5).
     *
     * @return the Moyasar hosted payment page URL to redirect the user to
     */
    public String initiatePayment(Booking booking) {
        String callbackUrl = appProperties.getBaseUrl()
                + "/bookings/" + booking.getId() + "/payment/callback";
        String description = "Cinema ticket – booking " + booking.getId()
                + " seats " + booking.getSeatNumber();

        // Network call — no DB connection held.
        MoyasarPaymentResponse response = moyasarClient.createPayment(
                booking.getId(), booking.getTotalAmount(), callbackUrl, description);

        String transactionUrl = response.getTransactionUrl();
        if (transactionUrl == null || transactionUrl.isBlank()) {
            throw new MoyasarException("Moyasar returned no transaction URL", 500);
        }

        // Tx1.5: persist the Moyasar payment ID for future cron/webhook lookups.
        if (response.getId() != null) {
            bookingRepository.saveMoyasarId(booking.getId(), response.getId());
        }

        return transactionUrl;
    }

    // ── Phase 3a: Browser Redirect ────────────────────────────────────────────
    // The user returns from Moyasar. We verify directly with Moyasar and do an
    // atomic conditional UPDATE (PENDING → CONFIRMED) to avoid double-confirming
    // if the webhook already ran.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Handle the browser redirect back from Moyasar after the user pays.
     * Verifies the payment amount against the DB and uses an atomic conditional
     * update so concurrent webhook confirmation is harmless.
     */
    public void confirmPayment(String bookingId, String moyasarPaymentId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));

        // Idempotency: already confirmed (webhook was faster).
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            log.info("Booking {} already confirmed — redirect path skipping duplicate", bookingId);
            return;
        }

        // If cancelled by cron while the user was on the payment page, the webhook
        // handler will deal with the late-payment refund. Nothing to do here.
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            log.warn("Booking {} is CANCELLED — payment redirect ignored; webhook will handle refund if needed", bookingId);
            throw new MoyasarException("Booking session expired. If payment was taken, a refund will be issued automatically.", 422);
        }

        // Verify with Moyasar — do NOT trust redirect query params alone.
        MoyasarPaymentResponse payment = moyasarClient.getPayment(moyasarPaymentId);
        if (!payment.isPaid()) {
            log.warn("Redirect: payment {} status={} for booking {} — releasing",
                    moyasarPaymentId, payment.getStatus(), bookingId);
            transactionTemplate.execute(status -> {
                releaseBookingInternal(bookingId);
                return null;
            });
            throw new MoyasarException("Payment not completed: " + payment.getStatus(), 422);
        }

        // Amount reconciliation.
        long expectedHalala = booking.getTotalAmount().multiply(BigDecimal.valueOf(100)).longValue();
        if (payment.getAmount() != null && !payment.getAmount().equals(expectedHalala)) {
            log.error("Amount mismatch for booking {}: expected {} halala, paid {}",
                    bookingId, expectedHalala, payment.getAmount());
            throw new MoyasarException("Payment amount mismatch", 422);
        }

        // Atomic conditional update: only moves PENDING → CONFIRMED once.
        int[] updatedHolder = {0};
        transactionTemplate.execute(txStatus -> {
            int rows = bookingRepository.confirmIfPending(bookingId, moyasarPaymentId);
            updatedHolder[0] = rows;
            if (rows == 1) {
                seatRepository.confirmSeat(bookingId);
                Booking confirmed = bookingRepository.findById(bookingId).orElseThrow();
                eventPublisher.publishEvent(new BookingConfirmedEvent(this, confirmed));
            }
            return null;
        });

        if (updatedHolder[0] == 0) {
            log.info("Booking {} was already transitioned (concurrent confirm or cancellation)", bookingId);
        }
    }

    // ── Phase 3b: Webhook ─────────────────────────────────────────────────────
    // Server-to-server path. Handles: normal confirmation, idempotent re-delivery,
    // and late payment on an already-CANCELLED booking (auto-refund).
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Process a "paid" webhook event from Moyasar.
     * <ul>
     *   <li>CONFIRMED — idempotent, return immediately.</li>
     *   <li>CANCELLED — late payment; refund immediately and mark REFUNDED_LATE_PAYMENT.</li>
     *   <li>PENDING — normal path; atomic conditional PENDING → CONFIRMED.</li>
     * </ul>
     */
    public void handleWebhookPayment(String bookingId, String moyasarPaymentId,
                                     Long webhookAmountHalala) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));

        // 1. Idempotency.
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            log.info("Webhook: booking {} already CONFIRMED — ignoring", bookingId);
            return;
        }

        // 2. Amount validation (tamper check using the DB amount, not webhook body).
        long expectedHalala = booking.getTotalAmount().multiply(BigDecimal.valueOf(100)).longValue();
        if (webhookAmountHalala != null && !webhookAmountHalala.equals(expectedHalala)) {
            log.error("Webhook amount mismatch for booking {}: expected {} halala, got {}",
                    bookingId, expectedHalala, webhookAmountHalala);
            throw new MoyasarException("Payment amount mismatch", 422);
        }

        // 3. Late payment: booking was cancelled while user was paying.
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            log.warn("Webhook: booking {} is CANCELLED but payment {} arrived — refunding immediately",
                    bookingId, moyasarPaymentId);
            try {
                moyasarClient.refundPayment(moyasarPaymentId, booking.getTotalAmount());
            } catch (MoyasarException e) {
                log.error("Auto-refund failed for late payment on booking {}: {}", bookingId, e.getMessage());
                // Still mark as REFUNDED_LATE_PAYMENT so an operator can follow up;
                // the row update is idempotent.
            }
            transactionTemplate.execute(txStatus -> {
                int rows = bookingRepository.markRefundedLatePayment(bookingId);
                if (rows == 1) {
                    Booking updated = bookingRepository.findById(bookingId).orElseThrow();
                    eventPublisher.publishEvent(new BookingRefundedLateEvent(this, updated));
                }
                return null;
            });
            return;
        }

        // 4. Normal path: PENDING → CONFIRMED (atomic conditional).
        int[] updatedHolder = {0};
        transactionTemplate.execute(txStatus -> {
            int rows = bookingRepository.confirmIfPending(bookingId, moyasarPaymentId);
            updatedHolder[0] = rows;
            if (rows == 1) {
                seatRepository.confirmSeat(bookingId);
                Booking confirmed = bookingRepository.findById(bookingId).orElseThrow();
                eventPublisher.publishEvent(new BookingConfirmedEvent(this, confirmed));
            }
            return null;
        });

        if (updatedHolder[0] == 0) {
            log.info("Webhook: booking {} was already transitioned (concurrent redirect or webhook)", bookingId);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @Transactional
    public void releaseBooking(String bookingId) {
        releaseBookingInternal(bookingId);
    }

    private void releaseBookingInternal(String bookingId) {
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null || booking.getStatus() != BookingStatus.PENDING) return;
        bookingRepository.updateStatus(bookingId, BookingStatus.CANCELLED, null);
        seatRepository.releaseSeat(bookingId);
    }

    /**
     * Admin-triggered cancellation with Moyasar refund.
     *
     * Workflow (crash-safe):
     * <ol>
     *   <li><b>DB (fast):</b> atomically move CONFIRMED → REFUND_PENDING. Seats stay
     *       held, so a crash here never double-frees a seat. Only one caller wins this race.</li>
     *   <li><b>Network:</b> request the refund from Moyasar (no DB transaction open).</li>
     *   <li><b>DB:</b> on success, REFUND_PENDING → CANCELLED and release the seats in one
     *       transaction. On Moyasar failure (money never left), revert to CONFIRMED.</li>
     * </ol>
     * If the process dies after Moyasar succeeds (step 3), the booking stays in
     * REFUND_PENDING and {@link #reconcilePendingRefunds()} repairs it later.
     */
    public void cancelBooking(String bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));

        // Idempotency: already done.
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return;
        }
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new IllegalStateException("Only CONFIRMED bookings can be cancelled. Current: " + booking.getStatus());
        }

        // ── Step 1: claim the booking for refunding (CONFIRMED → REFUND_PENDING). ──
        int claimed = transactionTemplate.execute(s -> bookingRepository.markRefundPending(bookingId));
        if (claimed == 0) {
            // Lost the race to a concurrent cancel/reconcile — nothing to do.
            log.info("Booking {} is already being refunded or cancelled (concurrent request)", bookingId);
            return;
        }

        // ── Step 2: refund via Moyasar (no transaction held during the network call). ──
        if (booking.getMoyasarPaymentId() != null) {
            try {
                moyasarClient.refundPayment(booking.getMoyasarPaymentId(), booking.getTotalAmount());
            } catch (MoyasarException e) {
                // Accept "already refunded" as success; otherwise the money never left.
                if (isAlreadyRefunded(booking.getMoyasarPaymentId())) {
                    log.info("Booking {} payment {} was already refunded at Moyasar — proceeding to cancel",
                            bookingId, booking.getMoyasarPaymentId());
                } else {
                    log.warn("Refund failed for booking {} — reverting to CONFIRMED: {}", bookingId, e.getMessage());
                    transactionTemplate.execute(s -> bookingRepository.revertRefundPendingToConfirmed(bookingId));
                    throw e;
                }
            }
        }

        // ── Step 3: finalize — REFUND_PENDING → CANCELLED and free the seats. ──
        final Booking bookingSnapshot = booking;
        transactionTemplate.execute(txStatus -> {
            int rows = bookingRepository.cancelIfRefundPending(bookingId);
            if (rows == 1) {
                seatRepository.releaseSeat(bookingId);
                eventPublisher.publishEvent(new BookingCancelledEvent(this, bookingSnapshot));
            }
            return null;
        });
    }

    /**
     * Query Moyasar to see whether a payment is already in the {@code refunded}
     * state. Used to treat a duplicate refund attempt as success.
     */
    private boolean isAlreadyRefunded(String moyasarPaymentId) {
        try {
            return moyasarClient.getPayment(moyasarPaymentId).isRefunded();
        } catch (MoyasarException e) {
            return false;
        }
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    public Optional<Booking> findById(String id) {
        return bookingRepository.findById(id);
    }

    public List<Booking> findByUserId(Long userId) {
        return bookingRepository.findByUserId(userId);
    }

    public List<Booking> findAll(BookingStatus status, Long movieId, int page, int size) {
        return bookingRepository.findAll(status, movieId, page, size);
    }

    public long countAll(BookingStatus status, Long movieId) {
        return bookingRepository.countAll(status, movieId);
    }

    // ── Cron: Sync expired PENDING bookings (every 5 minutes) ────────────────
    // For each expired PENDING booking, verify the real payment status with Moyasar
    // before deciding to cancel or confirm. This catches the edge case where both
    // the redirect and webhook failed but the payment went through.
    // ─────────────────────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 300_000)
    public void releaseExpiredHolds() {
        int holdMinutes = appProperties.getBooking().seatHoldMinutes();
        List<Booking> expired = bookingRepository.findExpiredPendingBookings(holdMinutes);

        for (Booking b : expired) {
            try {
                syncExpiredBooking(b);
            } catch (Exception e) {
                log.error("Failed to sync expired booking {}: {}", b.getId(), e.getMessage());
            }
        }
    }

    private void syncExpiredBooking(Booking booking) {
        String bookingId = booking.getId();

        // If we have a Moyasar payment ID, verify the real payment status.
        if (booking.getMoyasarPaymentId() != null) {
            MoyasarPaymentResponse payment;
            try {
                payment = moyasarClient.getPayment(booking.getMoyasarPaymentId());
            } catch (MoyasarException e) {
                log.warn("Cron: could not fetch Moyasar status for booking {} — skipping: {}", bookingId, e.getMessage());
                return;
            }

            if (payment.isPaid()) {
                // Payment succeeded but both webhook and redirect failed — confirm it now.
                log.info("Cron: booking {} has a successful payment {} — confirming", bookingId, booking.getMoyasarPaymentId());
                long expectedHalala = booking.getTotalAmount().multiply(BigDecimal.valueOf(100)).longValue();
                if (payment.getAmount() != null && !payment.getAmount().equals(expectedHalala)) {
                    log.error("Cron: amount mismatch for booking {} — skipping confirmation", bookingId);
                } else {
                    transactionTemplate.execute(txStatus -> {
                        // Re-select with FOR UPDATE to guard against concurrent changes.
                        int rows = bookingRepository.confirmIfPending(bookingId, booking.getMoyasarPaymentId());
                        if (rows == 1) {
                            seatRepository.confirmSeat(bookingId);
                            Booking confirmed = bookingRepository.findById(bookingId).orElseThrow();
                            eventPublisher.publishEvent(new BookingConfirmedEvent(this, confirmed));
                            log.info("Cron: booking {} confirmed via payment recovery", bookingId);
                        }
                        return null;
                    });
                }
                return;
            }
        }

        // Payment not made (or no Moyasar ID yet) — cancel and free seats.
        transactionTemplate.execute(txStatus -> {
            // Re-read inside the transaction to avoid TOCTOU.
            Booking fresh = bookingRepository.findById(bookingId).orElse(null);
            if (fresh == null || fresh.getStatus() != BookingStatus.PENDING) return null;

            log.info("Cron: cancelling expired booking {}", bookingId);
            bookingRepository.updateStatus(bookingId, BookingStatus.CANCELLED, null);
            seatRepository.releaseSeat(bookingId);
            return null;
        });
    }

    // ── Cron: Reconcile stuck REFUND_PENDING bookings (every 5 minutes) ──────
    // If the process died between a successful Moyasar refund and the final DB
    // update, a booking is left stuck in REFUND_PENDING. We query Moyasar for the
    // true payment state and either finish the cancellation (refunded) or revert
    // the booking to CONFIRMED (not refunded).
    // ─────────────────────────────────────────────────────────────────────────

    private static final int REFUND_PENDING_GRACE_MINUTES = 2;

    @Scheduled(fixedDelay = 300_000)
    public void reconcilePendingRefunds() {
        List<Booking> stuck = bookingRepository.findStuckRefundPendingBookings(REFUND_PENDING_GRACE_MINUTES);

        for (Booking b : stuck) {
            try {
                reconcileRefund(b);
            } catch (Exception e) {
                log.error("Failed to reconcile REFUND_PENDING booking {}: {}", b.getId(), e.getMessage());
            }
        }
    }

    private void reconcileRefund(Booking booking) {
        String bookingId = booking.getId();

        // No payment id on file: the booking was never charged, so just cancel & free seats.
        if (booking.getMoyasarPaymentId() == null) {
            log.warn("Reconcile: booking {} stuck in REFUND_PENDING with no Moyasar id — cancelling", bookingId);
            finalizeRefundCancellation(booking);
            return;
        }

        MoyasarPaymentResponse payment;
        try {
            payment = moyasarClient.getPayment(booking.getMoyasarPaymentId());
        } catch (MoyasarException e) {
            log.warn("Reconcile: could not fetch Moyasar status for booking {} — retrying next cycle: {}",
                    bookingId, e.getMessage());
            return;
        }

        if (payment.isRefunded()) {
            log.info("Reconcile: booking {} payment {} is refunded at Moyasar — finalizing cancellation",
                    bookingId, booking.getMoyasarPaymentId());
            finalizeRefundCancellation(booking);
        } else {
            log.info("Reconcile: booking {} payment {} is NOT refunded (status={}) — reverting to CONFIRMED",
                    bookingId, booking.getMoyasarPaymentId(), payment.getStatus());
            transactionTemplate.execute(s -> bookingRepository.revertRefundPendingToConfirmed(bookingId));
        }
    }

    private void finalizeRefundCancellation(Booking booking) {
        String bookingId = booking.getId();
        transactionTemplate.execute(txStatus -> {
            int rows = bookingRepository.cancelIfRefundPending(bookingId);
            if (rows == 1) {
                seatRepository.releaseSeat(bookingId);
                eventPublisher.publishEvent(new BookingCancelledEvent(this, booking));
            }
            return null;
        });
    }

    // ── Exceptions ───────────────────────────────────────────────────────────

    public static class SeatUnavailableException extends RuntimeException {
        public SeatUnavailableException(String message) { super(message); }
    }
}
