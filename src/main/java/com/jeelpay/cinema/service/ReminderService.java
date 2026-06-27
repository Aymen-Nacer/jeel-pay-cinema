package com.jeelpay.cinema.service;

import com.jeelpay.cinema.domain.Booking;
import com.jeelpay.cinema.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    private static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");

    private final BookingRepository bookingRepository;
    private final EmailService emailService;

    public ReminderService(BookingRepository bookingRepository, EmailService emailService) {
        this.bookingRepository = bookingRepository;
        this.emailService = emailService;
    }

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Riyadh")
    public void sendDayOfReminders() {
        LocalDate today = LocalDate.now(RIYADH);
        log.info("Running reminder job for date {} (Riyadh)", today);

        List<Booking> due = bookingRepository.findConfirmedBookingsForReminderOn(today);
        log.info("Found {} bookings due for reminder", due.size());

        for (Booking booking : due) {
            try {
                // Mark sent before emailing so overlapping scheduler runs stay idempotent.
                bookingRepository.markReminderSent(booking.getId());
                emailService.sendReminder(booking);
                log.info("Reminder sent for booking {}", booking.getId());
            } catch (Exception e) {
                log.error("Failed to send reminder for booking {}: {}", booking.getId(), e.getMessage());
            }
        }
    }
}
