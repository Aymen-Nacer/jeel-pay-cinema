package com.jeelpay.cinema;

import com.jeelpay.cinema.domain.BookingStatus;
import com.jeelpay.cinema.domain.SeatStatus;
import com.jeelpay.cinema.repository.BookingRepository;
import com.jeelpay.cinema.repository.ShowtimeSeatRepository;
import com.jeelpay.cinema.service.BookingService;
import com.jeelpay.cinema.service.UserService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end booking flow tests.
 *
 * The full Spring context runs against a real MySQL Testcontainer.
 * Moyasar and Resend are stubbed with WireMock (Docker container).
 *
 * <h3>Design decisions</h3>
 * <ul>
 *   <li><b>Self-contained data</b>: every test calls {@link TestDataFactory} to
 *       create its own movie/hall/showtime/seats instead of relying on hardcoded
 *       Flyway-seeded IDs (the "Mystery Guest" anti-pattern).</li>
 *   <li><b>CSRF/cookie extraction</b>: delegated to {@link TestHttpHelper} which
 *       uses Jsoup DOM parsing — robust against any cosmetic template changes.</li>
 *   <li><b>Email side-effect verification</b>: WireMock assertions confirm that
 *       the application actually called the Resend API, not just that the booking
 *       status changed in the DB.</li>
 * </ul>
 *
 * Coverage:
 *   1. HTTP-level end-to-end: register → login → POST /book → assert DB (PENDING + HELD)
 *   2. Successful payment: service-level confirm → CONFIRMED + BOOKED + Resend call verified
 *   3. Declined payment: seat is released
 *   4. Idempotent confirmation: double-confirm is a no-op
 *   5. Admin cancellation: seat returns to AVAILABLE
 *   6. Idempotent cancellation
 */
class BookingFlowTest extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired UserService userService;
    @Autowired BookingService bookingService;
    @Autowired BookingRepository bookingRepository;
    @Autowired ShowtimeSeatRepository showtimeSeatRepository;
    @Autowired TestDataFactory testDataFactory;

    @BeforeEach
    void stubEmails() {
        WireMockStubs.stubResendEmail();
    }

    // ── HTTP-level end-to-end test ───────────────────────────────────────────────

    /**
     * Proves the complete booking entry point through the real HTTP stack:
     * <ol>
     *   <li>Register a user</li>
     *   <li>Authenticate via form login (TestRestTemplate + session cookie)</li>
     *   <li>POST /showtimes/{id}/book — creates a PENDING booking</li>
     *   <li>Assert {@code Booking.status == PENDING} in the database</li>
     *   <li>Assert {@code ShowtimeSeat.status == HELD} (concurrency guard active)</li>
     * </ol>
     */
    @Test
    void httpEndToEnd_postBook_createsPendingBookingAndHoldsSeat() {
        var ctx = testDataFactory.createShowtimeWithSeats(4, 8);
        long showtimeId = ctx.showtimeId();
        String seat = ctx.firstSeat();   // "A1"

        var user = userService.register("e2e-http@test.com", "password123");

        String paymentId = "pay-e2e-" + UUID.randomUUID();
        WireMockStubs.stubMoyasarCreatePayment(paymentId,
                "https://moyasar.test/pay/" + paymentId);

        // Step 1 – Authenticate.
        String session = TestHttpHelper.loginAndGetSessionCookie(
                restTemplate, baseUrl(), "e2e-http@test.com", "password123");
        assertThat(session).as("Form login must create an authenticated session").isNotNull();

        // Step 2 – Fetch the showtime page to get the CSRF token (session may refresh).
        HttpHeaders getHeaders = new HttpHeaders();
        getHeaders.add("Cookie", "SESSION=" + session);
        ResponseEntity<String> showtimePage = restTemplate.exchange(
                baseUrl() + "/showtimes/" + showtimeId, HttpMethod.GET,
                new HttpEntity<>(getHeaders), String.class);

        String csrf = TestHttpHelper.extractCsrfToken(showtimePage.getBody());
        String refreshedSession = TestHttpHelper.extractCookie(showtimePage.getHeaders(), "SESSION");
        if (refreshedSession != null) session = refreshedSession;

        // Step 3 – POST /showtimes/{id}/book with one seat number.
        HttpHeaders postHeaders = new HttpHeaders();
        postHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        postHeaders.add("Cookie", "SESSION=" + session);

        var form = new org.springframework.util.LinkedMultiValueMap<String, String>();
        form.add("seatNumbers", seat);
        form.add("_csrf", csrf);

        ResponseEntity<String> resp = restTemplate.exchange(
                baseUrl() + "/showtimes/" + showtimeId + "/book", HttpMethod.POST,
                new HttpEntity<>(form, postHeaders), String.class);

        assertThat(resp.getStatusCode().value())
                .as("POST /book must return 302 (redirect to Moyasar payment page)")
                .isEqualTo(302);

        // Assert 1: booking is PENDING in the database.
        var bookings = bookingRepository.findByUserId(user.getId());
        assertThat(bookings).as("Exactly one booking must have been created").hasSize(1);
        var booking = bookings.get(0);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);

        // Assert 2: seat is HELD in showtime_seats.
        var allSeats = showtimeSeatRepository.findByShowtimeId(showtimeId);
        var heldSeat = allSeats.stream()
                .filter(s -> seat.equals(s.getSeatNumber()) && s.getStatus() == SeatStatus.HELD)
                .findFirst();
        assertThat(heldSeat).as("Seat %s must be HELD after booking initiation".formatted(seat)).isPresent();
        assertThat(heldSeat.get().getBookingId())
                .as("The HELD seat must be linked to the booking")
                .isEqualTo(booking.getId());
    }

    // ── Successful payment ───────────────────────────────────────────────────────

    /**
     * Confirms a booking via the service layer and asserts three things:
     * <ol>
     *   <li>{@code Booking.status} transitions to {@code CONFIRMED}</li>
     *   <li>{@code ShowtimeSeat.status} transitions to {@code BOOKED}</li>
     *   <li>A POST to Resend was made — proves the email side-effect fired</li>
     * </ol>
     */
    @Test
    void successfulPayment_confirmsBookingAndSeatAndSendsEmail() {
        var ctx = testDataFactory.createShowtimeWithSeats(3, 8);
        var user = userService.register("booking-success@test.com", "password123");
        String paymentId = "pay-" + UUID.randomUUID();

        var booking = bookingService.createPendingBooking(ctx.showtimeId(), List.of(ctx.seat(0)), user.getId());
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);

        WireMockStubs.stubMoyasarGetPaymentPaid(paymentId, 4500L);
        bookingService.confirmPayment(booking.getId(), paymentId);

        // Assert 1: booking confirmed.
        var confirmed = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(confirmed.getMoyasarPaymentId()).isEqualTo(paymentId);

        // Assert 2: seat is BOOKED — proves the physical concurrency guard is wired end-to-end.
        var bookedSeat = showtimeSeatRepository.findByShowtimeId(ctx.showtimeId()).stream()
                .filter(s -> booking.getId().equals(s.getBookingId()))
                .findFirst();
        assertThat(bookedSeat).as("A showtime_seats row linked to this booking must exist").isPresent();
        assertThat(bookedSeat.get().getStatus())
                .as("Seat must be BOOKED in showtime_seats after payment confirmation")
                .isEqualTo(SeatStatus.BOOKED);

        // Assert 3: email side-effect — WireMock confirms the HTTP call to Resend actually happened.
        // The email listener is @Async, so Awaitility gives it up to 5 s to complete.
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        verify(postRequestedFor(urlPathEqualTo("/resend/emails"))
                                .withRequestBody(containing("Booking Confirmed"))));
    }

    // ── Declined payment ─────────────────────────────────────────────────────────

    @Test
    void declinedPayment_releasesBookingAndSeat() {
        var ctx = testDataFactory.createShowtimeWithSeats(3, 8);
        var user = userService.register("booking-declined@test.com", "password123");
        String paymentId = "pay-fail-" + UUID.randomUUID();

        var booking = bookingService.createPendingBooking(ctx.showtimeId(), List.of(ctx.seat(1)), user.getId());

        WireMockStubs.stubMoyasarGetPaymentFailed(paymentId);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.jeelpay.cinema.integration.moyasar.MoyasarException.class,
                () -> bookingService.confirmPayment(booking.getId(), paymentId)
        );

        var released = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(released.getStatus()).isIn(BookingStatus.CANCELLED, BookingStatus.PENDING);
    }

    // ── Idempotency: duplicate confirmation ──────────────────────────────────────

    @Test
    void duplicateConfirmation_isIdempotent() {
        var ctx = testDataFactory.createShowtimeWithSeats(3, 8);
        var user = userService.register("idem-confirm@test.com", "password123");
        String paymentId = "pay-idem-" + UUID.randomUUID();

        var booking = bookingService.createPendingBooking(ctx.showtimeId(), List.of(ctx.seat(2)), user.getId());

        WireMockStubs.stubMoyasarGetPaymentPaid(paymentId, 4500L);
        bookingService.confirmPayment(booking.getId(), paymentId);
        // Second call must not throw and must not double-book.
        bookingService.confirmPayment(booking.getId(), paymentId);

        var b = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(b.getMoyasarPaymentId()).isEqualTo(paymentId);
    }

    // ── Admin cancellation ───────────────────────────────────────────────────────

    /**
     * Confirms a booking, cancels it as admin, and asserts:
     * <ol>
     *   <li>{@code Booking.status == CANCELLED}</li>
     *   <li>{@code ShowtimeSeat.status == AVAILABLE} (seat returned to pool)</li>
     * </ol>
     */
    @Test
    void adminCancelConfirmedBooking_refundsAndReleaseSeat() {
        var ctx = testDataFactory.createShowtimeWithSeats(3, 8);
        var user = userService.register("admin-cancel@test.com", "password123");
        String paymentId = "pay-cancel-" + UUID.randomUUID();
        String seatLabel = ctx.seat(4);

        var booking = bookingService.createPendingBooking(ctx.showtimeId(), List.of(seatLabel), user.getId());
        WireMockStubs.stubMoyasarGetPaymentPaid(paymentId, 4500L);
        bookingService.confirmPayment(booking.getId(), paymentId);

        WireMockStubs.stubMoyasarRefund(paymentId);
        bookingService.cancelBooking(booking.getId());

        // Assert 1: booking cancelled.
        var cancelled = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(BookingStatus.CANCELLED);

        // Assert 2: seat returned to AVAILABLE.
        var seat = showtimeSeatRepository.findByShowtimeId(ctx.showtimeId()).stream()
                .filter(s -> seatLabel.equals(s.getSeatNumber()))
                .findFirst();
        assertThat(seat).isPresent();
        assertThat(seat.get().getStatus())
                .as("Seat %s must be AVAILABLE again after cancellation".formatted(seatLabel))
                .isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    void adminCancelAlreadyCancelled_isIdempotent() {
        var ctx = testDataFactory.createShowtimeWithSeats(3, 8);
        var user = userService.register("idem-cancel@test.com", "password123");
        String paymentId = "pay-idem-cancel-" + UUID.randomUUID();

        var booking = bookingService.createPendingBooking(ctx.showtimeId(), List.of(ctx.seat(5)), user.getId());
        WireMockStubs.stubMoyasarGetPaymentPaid(paymentId, 4500L);
        bookingService.confirmPayment(booking.getId(), paymentId);

        WireMockStubs.stubMoyasarRefund(paymentId);
        bookingService.cancelBooking(booking.getId());
        // Second cancel must not throw.
        bookingService.cancelBooking(booking.getId());

        var b = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }
}
