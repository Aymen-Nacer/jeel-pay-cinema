package com.jeelpay.cinema.event;

import com.jeelpay.cinema.domain.Booking;
import org.springframework.context.ApplicationEvent;

public class BookingCancelledEvent extends ApplicationEvent {

    private final Booking booking;

    public BookingCancelledEvent(Object source, Booking booking) {
        super(source);
        this.booking = booking;
    }

    public Booking getBooking() { return booking; }
}
