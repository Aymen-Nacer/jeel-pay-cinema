package com.jeelpay.cinema.event;

import com.jeelpay.cinema.domain.Booking;
import org.springframework.context.ApplicationEvent;

public class BookingRefundedLateEvent extends ApplicationEvent {

    private final Booking booking;

    public BookingRefundedLateEvent(Object source, Booking booking) {
        super(source);
        this.booking = booking;
    }

    public Booking getBooking() { return booking; }
}
