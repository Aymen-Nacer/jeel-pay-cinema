package com.jeelpay.cinema.event;

import com.jeelpay.cinema.domain.Booking;
import org.springframework.context.ApplicationEvent;

public class BookingConfirmedEvent extends ApplicationEvent {

    private final Booking booking;

    public BookingConfirmedEvent(Object source, Booking booking) {
        super(source);
        this.booking = booking;
    }

    public Booking getBooking() { return booking; }
}
