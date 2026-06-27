package com.jeelpay.cinema;

import com.jeelpay.cinema.domain.BookingStatus;
import com.jeelpay.cinema.repository.BookingRepository;
import com.jeelpay.cinema.service.BookingService;
import com.jeelpay.cinema.service.UserService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Webhook confirmation tests — proves a booking is confirmed via the Moyasar
 * server-to-server webhook even if the browser never returns.
 *
 * <h3>Behavioral verification</h3>
 * Beyond checking {@code Booking.status == CONFIRMED} in the database, these
 * tests also verify that the application made a real HTTP call to Resend after
 * the webhook-triggered confirmation. WireMock records every inbound request,
 * so {@code verify(postRequestedFor(...))} is a precise assertion that the
 * email side-effect actually fired — not merely that the code path says it
 * should have.
 *
 * Showtimes are created by {@link TestDataFactory} so there is no dependence on
 * Flyway seed IDs (Mystery Guest anti-pattern).
 */
class WebhookConfirmationTest extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired UserService userService;
    @Autowired BookingService bookingService;
    @Autowired BookingRepository bookingRepository;
    @Autowired TestDataFactory testDataFactory;

    @BeforeEach
    void stubEmails() {
        WireMockStubs.stubResendEmail();
    }

    // ── Tests ────────────────────────────────────────────────────────────────────

    @Test
    void webhook_confirmsBooking_andSendsConfirmationEmail() {
        var ctx = testDataFactory.createShowtimeWithSeats(3, 8);
        var user = userService.register("webhook-success@test.com", "password123");
        String paymentId = "pay-wh-" + UUID.randomUUID();

        var booking = bookingService.createPendingBooking(
                ctx.showtimeId(), java.util.List.of(ctx.firstSeat()), user.getId());
        WireMockStubs.stubMoyasarGetPaymentPaid(paymentId, 4500L);

        ResponseEntity<String> resp = postWebhook(booking.getId(), paymentId, "paid");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        // Assert 1: booking confirmed in DB.
        var confirmed = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(confirmed.getMoyasarPaymentId()).isEqualTo(paymentId);

        // Assert 2: Resend was actually called — WireMock proves the HTTP call happened,
        // not just that the code path says it should have. The listener is @Async;
        // Awaitility gives it up to 5 s to arrive at the WireMock container.
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        verify(postRequestedFor(urlPathEqualTo("/resend/emails"))
                                .withRequestBody(containing("Booking Confirmed"))));
    }

    @Test
    void webhook_isIdempotent_onDuplicateDelivery() {
        var ctx = testDataFactory.createShowtimeWithSeats(3, 8);
        var user = userService.register("webhook-idem@test.com", "password123");
        String paymentId = "pay-wh-idem-" + UUID.randomUUID();

        var booking = bookingService.createPendingBooking(
                ctx.showtimeId(), java.util.List.of(ctx.seat(1)), user.getId());
        WireMockStubs.stubMoyasarGetPaymentPaid(paymentId, 4500L);

        // Moyasar delivers the same event twice (at-least-once delivery guarantee).
        assertThat(postWebhook(booking.getId(), paymentId, "paid").getStatusCode().value()).isEqualTo(200);
        assertThat(postWebhook(booking.getId(), paymentId, "paid").getStatusCode().value()).isEqualTo(200);

        var confirmed = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(confirmed.getMoyasarPaymentId()).isEqualTo(paymentId);

        // The confirmation email must have been sent at least once despite the duplicate delivery.
        // (The idempotency guard on the booking side prevents double-confirmation; any
        // additional email sends would be caught by more targeted email-idempotency tests.)
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        verify(postRequestedFor(urlPathEqualTo("/resend/emails"))
                                .withRequestBody(containing("Booking Confirmed"))));
    }

    // ── HTTP helper ──────────────────────────────────────────────────────────────

    private ResponseEntity<String> postWebhook(String bookingId, String paymentId, String status) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", paymentId);
        data.put("status", status);
        data.put("metadata", Map.of("booking_id", bookingId));

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "payment_paid");
        payload.put("data", data);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(
                baseUrl() + "/webhooks/moyasar",
                new HttpEntity<>(payload, headers),
                String.class);
    }
}
