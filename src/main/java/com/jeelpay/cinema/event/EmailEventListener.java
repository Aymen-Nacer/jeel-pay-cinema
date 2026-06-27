package com.jeelpay.cinema.event;

import com.jeelpay.cinema.service.EmailService;
import com.jeelpay.cinema.event.BookingRefundedLateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Dispatches transactional emails after the DB transaction commits.
 *
 * Each handler is annotated with both:
 *   {@code @TransactionalEventListener(AFTER_COMMIT)} — fires only after the
 *       business transaction is durable, so emails are never sent for rolled-back
 *       operations, and email failures cannot roll back the business transaction.
 *   {@code @Async} — executes in a Spring-managed async thread pool so the HTTP
 *       request thread is never blocked waiting for the email to send.
 *
 * For this to work the calling transaction must have already committed before the
 * async thread starts, which is exactly what AFTER_COMMIT guarantees. Spring
 * correctly sequences the async dispatch to happen post-commit.
 */
@Component
public class EmailEventListener {

    private static final Logger log = LoggerFactory.getLogger(EmailEventListener.class);

    private final EmailService emailService;

    public EmailEventListener(EmailService emailService) {
        this.emailService = emailService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserRegistered(UserRegisteredEvent event) {
        try {
            emailService.sendWelcome(event.getUser());
        } catch (Exception e) {
            log.error("Welcome email failed for {}: {}", event.getUser().getEmail(), e.getMessage());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingConfirmed(BookingConfirmedEvent event) {
        try {
            emailService.sendBookingConfirmation(event.getBooking());
        } catch (Exception e) {
            log.error("Confirmation email failed for booking {}: {}", event.getBooking().getId(), e.getMessage());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingCancelled(BookingCancelledEvent event) {
        try {
            emailService.sendCancellation(event.getBooking());
        } catch (Exception e) {
            log.error("Cancellation email failed for booking {}: {}", event.getBooking().getId(), e.getMessage());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingRefundedLate(BookingRefundedLateEvent event) {
        try {
            emailService.sendLatePaymentRefund(event.getBooking());
        } catch (Exception e) {
            log.error("Late-payment refund email failed for booking {}: {}", event.getBooking().getId(), e.getMessage());
        }
    }
}
