package com.jeelpay.cinema;

import com.jeelpay.cinema.domain.Booking;
import com.jeelpay.cinema.repository.BookingRepository;
import com.jeelpay.cinema.service.BookingService;
import com.jeelpay.cinema.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
                ctx.showtimeId(), java.util.List.of(ctx.firstSeat()), user.getId());
        WireMockStubs.confirmBookingViaPaidPayment(bookingService, booking, paymentId, 4500L);

        var confirmed = bookingRepository.findById(booking.getId()).orElseThrow();
        // JDBC reads UTC timestamps as local time; matches CONVERT_TZ(..., '+03:00') in the query.
        LocalDate riyadhDate = confirmed.getShowtimeStart().toLocalDate();

        assertThat(bookingRepository.findConfirmedBookingsForReminderOn(riyadhDate))
                .extracting(Booking::getId)
                .contains(confirmed.getId());

        assertThat(bookingRepository.findConfirmedBookingsForReminderOn(riyadhDate.minusDays(1)))
                .extracting(Booking::getId)
                .doesNotContain(confirmed.getId());

        bookingRepository.markReminderSent(confirmed.getId());
        assertThat(bookingRepository.findConfirmedBookingsForReminderOn(riyadhDate))
                .extracting(Booking::getId)
                .doesNotContain(confirmed.getId());
    }
}
