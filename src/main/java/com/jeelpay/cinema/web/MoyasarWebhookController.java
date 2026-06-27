package com.jeelpay.cinema.web;

import com.jeelpay.cinema.config.AppProperties;
import com.jeelpay.cinema.service.BookingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Server-to-server payment confirmation from Moyasar (webhook).
 *
 * Delegates to {@link BookingService#handleWebhookPayment} which:
 * <ul>
 *   <li>Is fully idempotent (CONFIRMED → no-op).</li>
 *   <li>Handles late payments on CANCELLED bookings with an auto-refund.</li>
 *   <li>Never trusts the webhook body alone — the service layer re-verifies amounts
 *       against the DB record.</li>
 * </ul>
 */
@RestController
public class MoyasarWebhookController {

    private static final Logger log = LoggerFactory.getLogger(MoyasarWebhookController.class);

    private final BookingService bookingService;
    private final AppProperties props;

    public MoyasarWebhookController(BookingService bookingService, AppProperties props) {
        this.bookingService = bookingService;
        this.props = props;
    }

    @PostMapping("/webhooks/moyasar")
    public ResponseEntity<Void> handle(@RequestBody Map<String, Object> payload) {
        String configuredSecret = props.getMoyasar().webhookSecret();
        if (configuredSecret != null && !configuredSecret.isBlank()) {
            Object token = payload.get("secret_token");
            if (!configuredSecret.equals(token)) {
                log.warn("Rejected Moyasar webhook with invalid secret_token");
                return ResponseEntity.status(401).build();
            }
        }

        String type = asString(payload.get("type"));
        Object dataObj = payload.get("data");
        if (!(dataObj instanceof Map<?, ?> data)) {
            return ResponseEntity.ok().build();
        }

        String paymentId = asString(data.get("id"));
        String status    = asString(data.get("status"));
        String bookingId = extractBookingId(data.get("metadata"));

        if (paymentId == null || bookingId == null) {
            log.warn("Moyasar webhook missing payment id or booking metadata (type={})", type);
            return ResponseEntity.ok().build();
        }

        boolean paid = "paid".equalsIgnoreCase(status) || "payment_paid".equalsIgnoreCase(type);
        if (!paid) {
            log.info("Ignoring non-paid Moyasar webhook for booking {} (type={}, status={})",
                    bookingId, type, status);
            return ResponseEntity.ok().build();
        }

        // Extract the amount from the webhook body for tamper-checking in the service.
        Long amountHalala = extractAmount(data.get("amount"));

        try {
            bookingService.handleWebhookPayment(bookingId, paymentId, amountHalala);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Webhook processing failed for booking {} payment {}: {}",
                    bookingId, paymentId, e.getMessage());
            // Return 5xx so Moyasar retries transient failures.
            return ResponseEntity.status(500).build();
        }
    }

    private static String asString(Object o) {
        return o != null ? o.toString() : null;
    }

    private static Long extractAmount(Object o) {
        if (o == null) return null;
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String extractBookingId(Object metadata) {
        if (!(metadata instanceof Map<?, ?> map)) return null;
        Object bookingId = map.get("booking_id");
        return bookingId != null ? bookingId.toString() : null;
    }
}
