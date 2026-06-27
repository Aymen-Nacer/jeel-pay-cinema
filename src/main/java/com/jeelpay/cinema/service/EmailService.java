package com.jeelpay.cinema.service;

import com.jeelpay.cinema.domain.Booking;
import com.jeelpay.cinema.domain.User;
import com.jeelpay.cinema.integration.resend.ResendClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("EEE, MMM d yyyy HH:mm");

    private final ResendClient resendClient;

    public EmailService(ResendClient resendClient) {
        this.resendClient = resendClient;
    }

    public void sendWelcome(User user) {
        String subject = "Welcome to Jeel Pay Cinema!";
        String html = """
                <h2>Welcome, %s!</h2>
                <p>Your account has been created successfully.</p>
                <p>Browse our movies and book your seats at <a href="/">Jeel Pay Cinema</a>.</p>
                """.formatted(user.getEmail());
        send(user.getEmail(), subject, html);
    }

    public void sendBookingConfirmation(Booking booking) {
        String subject = "Booking Confirmed – " + booking.getMovieTitle();
        String html = """
                <h2>Your booking is confirmed!</h2>
                <table>
                  <tr><td><strong>Movie:</strong></td><td>%s</td></tr>
                  <tr><td><strong>Date &amp; Time:</strong></td><td>%s</td></tr>
                  <tr><td><strong>Hall:</strong></td><td>%s</td></tr>
                  <tr><td><strong>Seat:</strong></td><td>%s</td></tr>
                  <tr><td><strong>Amount Paid:</strong></td><td>SAR %.2f</td></tr>
                </table>
                <p>Enjoy the show!</p>
                """.formatted(
                booking.getMovieTitle(),
                booking.getShowtimeStart().format(DISPLAY_FMT),
                booking.getHallName(),
                booking.getSeatNumber(),
                booking.getTotalAmount());
        send(booking.getUserEmail(), subject, html);
    }

    public void sendCancellation(Booking booking) {
        String subject = "Booking Cancelled – " + booking.getMovieTitle();
        String html = """
                <h2>Your booking has been cancelled</h2>
                <p>Your booking for <strong>%s</strong> on %s (Seat %s) has been cancelled.</p>
                <p>A full refund of <strong>SAR %.2f</strong> has been issued to your original payment method.</p>
                <p>If you have questions, please contact us.</p>
                """.formatted(
                booking.getMovieTitle(),
                booking.getShowtimeStart().format(DISPLAY_FMT),
                booking.getSeatNumber(),
                booking.getTotalAmount());
        send(booking.getUserEmail(), subject, html);
    }

    public void sendLatePaymentRefund(Booking booking) {
        String subject = "Refund Issued – Payment Received After Session Expired";
        String html = """
                <h2>Your payment has been refunded</h2>
                <p>We received your payment for <strong>%s</strong> (Seat %s), but your booking
                session had already expired by the time the payment was processed.</p>
                <p>A full refund of <strong>SAR %.2f</strong> has been issued automatically to your
                original payment method. Please allow a few business days for it to appear.</p>
                <p>We apologise for the inconvenience. You are welcome to book again at
                <a href="/">Jeel Pay Cinema</a>.</p>
                """.formatted(
                booking.getMovieTitle(),
                booking.getSeatNumber(),
                booking.getTotalAmount());
        send(booking.getUserEmail(), subject, html);
    }

    public void sendReminder(Booking booking) {
        String subject = "Reminder: " + booking.getMovieTitle() + " is today!";
        String html = """
                <h2>Don't forget your movie tonight!</h2>
                <table>
                  <tr><td><strong>Movie:</strong></td><td>%s</td></tr>
                  <tr><td><strong>Time:</strong></td><td>%s</td></tr>
                  <tr><td><strong>Hall:</strong></td><td>%s</td></tr>
                  <tr><td><strong>Seat:</strong></td><td>%s</td></tr>
                </table>
                <p>See you there!</p>
                """.formatted(
                booking.getMovieTitle(),
                booking.getShowtimeStart().format(DISPLAY_FMT),
                booking.getHallName(),
                booking.getSeatNumber());
        send(booking.getUserEmail(), subject, html);
    }

    private void send(String to, String subject, String html) {
        try {
            resendClient.sendEmail(to, subject, html);
        } catch (Exception e) {
            // Email failures must never propagate — the underlying action has already committed.
            log.error("Failed to send email to {} subject='{}': {}", to, subject, e.getMessage());
        }
    }
}
