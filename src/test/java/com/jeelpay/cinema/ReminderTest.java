package com.jeelpay.cinema;

import com.jeelpay.cinema.domain.Booking;
import com.jeelpay.cinema.repository.BookingRepository;
import com.jeelpay.cinema.service.BookingService;
import com.jeelpay.cinema.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reminder "is it due?" decision + idempotency.
 *
 * The due-date decision lives in the repository query ({@code CONVERT_TZ} to
 * Asia/Riyadh), so it is exercised here against real MySQL. We also prove the
 * {@code reminder_sent} flag makes the job idempotent: once flagged, a booking
 * is no longer selected.
 *
 * The showtime is created via {@link TestDataFactory} so the test does not
 * depend on hardcoded Flyway seed IDs (Mystery Guest anti-pattern).
 */
class ReminderTest extends AbstractIntegrationTest {

    @Autowired UserService userService;
    @Autowired BookingService bookingService;
    @Autowired BookingRepository bookingRepository;
    @Autowired TestDataFactory testDataFactory;

    @BeforeEach
    void stubEmails() {
        WireMockStubs.stubResendEmail();
    }

    @Test
    void confirmedBooking_isDueOnShowtimeRiyadhDate_andRemindedOnlyOnce() {
        var ctx = testDataFactory.createShowtimeWithSeats(4, 8);
        var user = userService.register("reminder@test.com", "password123");
        String paymentId = "pay-rem-" + UUID.randomUUID();

        var booking = bookingService.createPendingBooking(
                ctx.showtimeId(), List.of(ctx.firstSeat()), user.getId());
        WireMockStubs.stubMoyasarGetPaymentPaid(paymentId, 4500L);
        bookingService.confirmPayment(booking.getId(), paymentId);

        var confirmed = bookingRepository.findById(booking.getId()).orElseThrow();
        // The JDBC driver (serverTimezone=UTC) converts the stored UTC datetime to the
        // JVM's local timezone when reading via getTimestamp().toLocalDateTime(), so
        // getShowtimeStart() already returns the Riyadh-local time (+03:00).
        // The SQL CONVERT_TZ(start_time, '+00:00', '+03:00') produces the same result.
        // No manual +3h adjustment is needed here.
        LocalDate riyadhDate = confirmed.getShowtimeStart().toLocalDate();

        // Due on the correct Riyadh date…
        assertThat(bookingRepository.findConfirmedBookingsForReminderOn(riyadhDate))
                .extracting(Booking::getId)
                .contains(confirmed.getId());

        // …but NOT on the day before.
        assertThat(bookingRepository.findConfirmedBookingsForReminderOn(riyadhDate.minusDays(1)))
                .extracting(Booking::getId)
                .doesNotContain(confirmed.getId());

        // Once the reminder is sent, the same booking must not be selected again
        // (idempotency — safe across repeated scheduler runs or application restarts).
        bookingRepository.markReminderSent(confirmed.getId());
        assertThat(bookingRepository.findConfirmedBookingsForReminderOn(riyadhDate))
                .extracting(Booking::getId)
                .doesNotContain(confirmed.getId());
    }
}
