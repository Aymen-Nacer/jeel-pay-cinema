package com.jeelpay.cinema.event;

import com.jeelpay.cinema.domain.Booking;
import org.springframework.context.ApplicationEvent;

/**
 * Published when a user pays after the booking was already cancelled by the cron job.
 * The payment is immediately refunded; this event triggers a notification email.
 */
public class BookingRefundedLateEvent extends ApplicationEvent {

    private final Booking booking;

    public BookingRefundedLateEvent(Object source, Booking booking) {
        super(source);
        this.booking = booking;
    }

    public Booking getBooking() { return booking; }
}
