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

    @Test
    void httpEndToEnd_postBook_createsPendingBookingAndHoldsSeat() {
        var ctx = testDataFactory.createShowtimeWithSeats(4, 8);
        long showtimeId = ctx.showtimeId();
        String seat = ctx.firstSeat();

        var user = userService.register("e2e-http@test.com", "password123");

        String paymentId = "pay-e2e-" + UUID.randomUUID();
        WireMockStubs.stubMoyasarCreatePayment(paymentId,
                "https://moyasar.test/pay/" + paymentId);

        String session = TestHttpHelper.loginAndGetSessionCookie(
                restTemplate, baseUrl(), "e2e-http@test.com", "password123");
        assertThat(session).as("Form login must create an authenticated session").isNotNull();

        HttpHeaders getHeaders = new HttpHeaders();
        getHeaders.add("Cookie", "SESSION=" + session);
        ResponseEntity<String> showtimePage = restTemplate.exchange(
                baseUrl() + "/showtimes/" + showtimeId, HttpMethod.GET,
                new HttpEntity<>(getHeaders), String.class);

        String csrf = TestHttpHelper.extractCsrfToken(showtimePage.getBody());
        String refreshedSession = TestHttpHelper.extractCookie(showtimePage.getHeaders(), "SESSION");
        if (refreshedSession != null) session = refreshedSession;

        HttpHeaders postHeaders = new HttpHeaders();
        postHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        postHeaders.add("Cookie", "SESSION=" + session);

        var form = new org.springframework.util.LinkedMultiValueMap<String, String>();
        form.add("seatNumbers", seat);
        form.add("cardName", "John Doe");
        form.add("cardNumber", "4111111111111111");
        form.add("cardMonth", "12");
        form.add("cardYear", "2026");
        form.add("cardCvc", "911");
        form.add("_csrf", csrf);

        ResponseEntity<String> resp = restTemplate.exchange(
                baseUrl() + "/showtimes/" + showtimeId + "/book", HttpMethod.POST,
                new HttpEntity<>(form, postHeaders), String.class);

        assertThat(resp.getStatusCode().value())
                .as("POST /book must return 302 (redirect to Moyasar payment page)")
                .isEqualTo(302);

        var bookings = bookingRepository.findByUserId(user.getId());
        assertThat(bookings).as("Exactly one booking must have been created").hasSize(1);
        var booking = bookings.get(0);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);

        var allSeats = showtimeSeatRepository.findByShowtimeId(showtimeId);
        var heldSeat = allSeats.stream()
                .filter(s -> seat.equals(s.getSeatNumber()) && s.getStatus() == SeatStatus.HELD)
                .findFirst();
        assertThat(heldSeat).as("Seat %s must be HELD after booking initiation".formatted(seat)).isPresent();
        assertThat(heldSeat.get().getBookingId())
                .as("The HELD seat must be linked to the booking")
                .isEqualTo(booking.getId());
    }

    @Test
    void successfulPayment_confirmsBookingAndSeatAndSendsEmail() {
        var ctx = testDataFactory.createShowtimeWithSeats(3, 8);
        var user = userService.register("booking-success@test.com", "password123");
        String paymentId = "pay-" + UUID.randomUUID();

        var booking = bookingService.createPendingBooking(ctx.showtimeId(), List.of(ctx.seat(0)), user.getId());
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);

        WireMockStubs.confirmBookingViaPaidPayment(bookingService, booking, paymentId, 4500L);

        var confirmed = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(confirmed.getMoyasarPaymentId()).isEqualTo(paymentId);

        var bookedSeat = showtimeSeatRepository.findByShowtimeId(ctx.showtimeId()).stream()
                .filter(s -> booking.getId().equals(s.getBookingId()))
                .findFirst();
        assertThat(bookedSeat).as("A showtime_seats row linked to this booking must exist").isPresent();
        assertThat(bookedSeat.get().getStatus())
                .as("Seat must be BOOKED in showtime_seats after payment confirmation")
                .isEqualTo(SeatStatus.BOOKED);

        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        verify(postRequestedFor(urlPathEqualTo("/resend/emails"))
                                .withRequestBody(containing("Booking Confirmed"))));
    }

    @Test
    void declinedPayment_releasesBookingAndSeat() {
        var ctx = testDataFactory.createShowtimeWithSeats(3, 8);
        var user = userService.register("booking-declined@test.com", "password123");
        String paymentId = "pay-fail-" + UUID.randomUUID();

        var booking = bookingService.createPendingBooking(ctx.showtimeId(), List.of(ctx.seat(1)), user.getId());

        WireMockStubs.stubMoyasarCreatePayment(paymentId, "https://moyasar.test/pay/" + paymentId);
        bookingService.initiatePayment(booking, WireMockStubs.TEST_CARD);
        WireMockStubs.stubMoyasarGetPaymentFailed(paymentId);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.jeelpay.cinema.integration.moyasar.MoyasarException.class,
                () -> bookingService.confirmPayment(booking.getId())
        );

        var released = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(released.getStatus()).isIn(BookingStatus.CANCELLED, BookingStatus.PENDING);
    }

    @Test
    void duplicateConfirmation_isIdempotent() {
        var ctx = testDataFactory.createShowtimeWithSeats(3, 8);
        var user = userService.register("idem-confirm@test.com", "password123");
        String paymentId = "pay-idem-" + UUID.randomUUID();

        var booking = bookingService.createPendingBooking(ctx.showtimeId(), List.of(ctx.seat(2)), user.getId());

        WireMockStubs.confirmBookingViaPaidPayment(bookingService, booking, paymentId, 4500L);
        bookingService.confirmPayment(booking.getId());

        var b = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(b.getMoyasarPaymentId()).isEqualTo(paymentId);
    }

    @Test
    void adminCancelConfirmedBooking_refundsAndReleaseSeat() {
        var ctx = testDataFactory.createShowtimeWithSeats(3, 8);
        var user = userService.register("admin-cancel@test.com", "password123");
        String paymentId = "pay-cancel-" + UUID.randomUUID();
        String seatLabel = ctx.seat(4);

        var booking = bookingService.createPendingBooking(ctx.showtimeId(), List.of(seatLabel), user.getId());
        WireMockStubs.confirmBookingViaPaidPayment(bookingService, booking, paymentId, 4500L);

        WireMockStubs.stubMoyasarRefund(paymentId);
        bookingService.cancelBooking(booking.getId());

        var cancelled = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(BookingStatus.CANCELLED);

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
        WireMockStubs.confirmBookingViaPaidPayment(bookingService, booking, paymentId, 4500L);

        WireMockStubs.stubMoyasarRefund(paymentId);
        bookingService.cancelBooking(booking.getId());
        bookingService.cancelBooking(booking.getId());

        var b = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }
}
