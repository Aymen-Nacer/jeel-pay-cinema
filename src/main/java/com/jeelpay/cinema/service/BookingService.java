package com.jeelpay.cinema.service;

import com.jeelpay.cinema.config.AppProperties;
import com.jeelpay.cinema.domain.*;
import com.jeelpay.cinema.event.BookingCancelledEvent;
import com.jeelpay.cinema.event.BookingConfirmedEvent;
import com.jeelpay.cinema.event.BookingRefundedLateEvent;
import com.jeelpay.cinema.integration.moyasar.CardDetails;
import com.jeelpay.cinema.integration.moyasar.MoyasarClient;
import com.jeelpay.cinema.integration.moyasar.MoyasarException;
import com.jeelpay.cinema.integration.moyasar.MoyasarPaymentResponse;
import com.jeelpay.cinema.repository.BookingRepository;
import com.jeelpay.cinema.repository.ShowtimeRepository;
import com.jeelpay.cinema.repository.ShowtimeSeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookingRepository;
    private final ShowtimeRepository showtimeRepository;
    private final ShowtimeSeatRepository seatRepository;
    private final MoyasarClient moyasarClient;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final AppProperties appProperties;

    public BookingService(BookingRepository bookingRepository,
                          ShowtimeRepository showtimeRepository,
                          ShowtimeSeatRepository seatRepository,
                          MoyasarClient moyasarClient,
                          ApplicationEventPublisher eventPublisher,
                          TransactionTemplate transactionTemplate,
                          AppProperties appProperties) {
        this.bookingRepository = bookingRepository;
        this.showtimeRepository = showtimeRepository;
        this.seatRepository = seatRepository;
        this.moyasarClient = moyasarClient;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
        this.appProperties = appProperties;
    }

    @Transactional
    public Booking createPendingBooking(Long showtimeId, List<String> seatNumbers, Long userId) {
        if (seatNumbers == null || seatNumbers.isEmpty()) {
            throw new IllegalArgumentException("At least one seat must be selected");
        }

        Showtime showtime = showtimeRepository.findByIdBasic(showtimeId)
                .orElseThrow(() -> new IllegalArgumentException("Showtime not found: " + showtimeId));

        List<ShowtimeSeat> seats;
        try {
            seats = seatRepository.lockMultipleForUpdate(showtimeId, seatNumbers);
        } catch (DataAccessException ex) {
            throw new SeatUnavailableException("One or more seats are currently being booked — please try again");
        }

        if (seats.size() != seatNumbers.size()) {
            List<String> found = seats.stream().map(ShowtimeSeat::getSeatNumber).collect(Collectors.toList());
            List<String> missing = seatNumbers.stream().filter(n -> !found.contains(n)).collect(Collectors.toList());
            throw new IllegalArgumentException("Seats not found: " + missing);
        }

        for (ShowtimeSeat seat : seats) {
            if (seat.getStatus() != SeatStatus.AVAILABLE) {
                throw new SeatUnavailableException("Seat " + seat.getSeatNumber() + " is no longer available");
            }
        }

        BigDecimal total = showtime.getPrice().multiply(BigDecimal.valueOf(seats.size()));

        Booking booking = new Booking();
        booking.setUserId(userId);
        booking.setShowtimeId(showtimeId);
        booking.setSeatIds(seats.stream().map(ShowtimeSeat::getSeatId).collect(Collectors.toList()));
        booking.setStatus(BookingStatus.PENDING);
        booking.setTotalAmount(total);

        booking = bookingRepository.save(booking);

        List<Long> showTimeSeatIds = seats.stream().map(ShowtimeSeat::getId).collect(Collectors.toList());
        seatRepository.holdSeats(showTimeSeatIds, booking.getId());

        String label = seats.stream().map(ShowtimeSeat::getSeatNumber).collect(Collectors.joining(", "));
        booking.setSeatNumber(label);
        return booking;
    }

    public String initiatePayment(Booking booking, CardDetails card) {
        String callbackUrl = appProperties.getBaseUrl()
                + "/bookings/" + booking.getId() + "/payment/callback";
        String description = "Cinema ticket – booking " + booking.getId()
                + " seats " + booking.getSeatNumber();

        MoyasarPaymentResponse payment = moyasarClient.createPayment(
                booking.getId(), booking.getTotalAmount(), callbackUrl, description, card);

        if (payment.getId() == null) {
            throw new MoyasarException("Moyasar returned no payment id", 500);
        }

        String transactionUrl = payment.getTransactionUrl();
        if (transactionUrl == null || transactionUrl.isBlank()) {
            throw new MoyasarException("Moyasar returned no 3-D Secure transaction URL", 500);
        }

        bookingRepository.saveMoyasarId(booking.getId(), payment.getId());

        return transactionUrl;
    }

    public void confirmPayment(String bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));

        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            log.info("Booking {} already confirmed — redirect path skipping duplicate", bookingId);
            return;
        }

        if (booking.getStatus() == BookingStatus.CANCELLED) {
            log.warn("Booking {} is CANCELLED — payment redirect ignored; webhook will handle refund if needed", bookingId);
            throw new MoyasarException("Booking session expired. If payment was taken, a refund will be issued automatically.", 422);
        }

        if (booking.getMoyasarPaymentId() == null) {
            log.warn("Redirect: booking {} has no Moyasar payment id — releasing", bookingId);
            transactionTemplate.execute(status -> { releaseBookingInternal(bookingId); return null; });
            throw new MoyasarException("Payment was not initiated correctly.", 422);
        }

        // Redirect query params are not trusted — verify payment status with Moyasar directly.
        MoyasarPaymentResponse payment = moyasarClient.getPayment(booking.getMoyasarPaymentId());
        if (!payment.isPaid()) {
            log.warn("Redirect: payment {} status={} for booking {} — releasing",
                    booking.getMoyasarPaymentId(), payment.getStatus(), bookingId);
            transactionTemplate.execute(status -> {
                releaseBookingInternal(bookingId);
                return null;
            });
            throw new MoyasarException("Payment not completed: " + payment.getStatus(), 422);
        }

        long expectedHalala = booking.getTotalAmount().multiply(BigDecimal.valueOf(100)).longValue();
        Long paidHalala = payment.getAmount();
        if (paidHalala != null && !paidHalala.equals(expectedHalala)) {
            log.error("Amount mismatch for booking {}: expected {} halala, paid {}",
                    bookingId, expectedHalala, paidHalala);
            throw new MoyasarException("Payment amount mismatch", 422);
        }

        String moyasarPaymentId = payment.getId();

        int[] updatedHolder = {0};
        transactionTemplate.execute(txStatus -> {
            int rows = bookingRepository.confirmIfPending(bookingId, moyasarPaymentId);
            updatedHolder[0] = rows;
            if (rows == 1) {
                seatRepository.confirmSeat(bookingId);
                Booking confirmed = bookingRepository.findById(bookingId).orElseThrow();
                eventPublisher.publishEvent(new BookingConfirmedEvent(this, confirmed));
            }
            return null;
        });

        if (updatedHolder[0] == 0) {
            log.info("Booking {} was already transitioned (concurrent confirm or cancellation)", bookingId);
        }
    }

    public void handleWebhookPayment(String bookingId, String moyasarPaymentId, Long webhookAmountHalala) {
        Booking booking = resolveBooking(bookingId, moyasarPaymentId);
        if (booking == null) {
            throw new IllegalArgumentException(
                    "Booking not found for webhook (bookingId=" + bookingId + ", paymentId=" + moyasarPaymentId + ")");
        }
        final String resolvedId = booking.getId();

        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            log.info("Webhook: booking {} already CONFIRMED — ignoring", resolvedId);
            return;
        }

        long expectedHalala = booking.getTotalAmount().multiply(BigDecimal.valueOf(100)).longValue();
        if (webhookAmountHalala != null && !webhookAmountHalala.equals(expectedHalala)) {
            log.error("Webhook amount mismatch for booking {}: expected {} halala, got {}",
                    resolvedId, expectedHalala, webhookAmountHalala);
            throw new MoyasarException("Payment amount mismatch", 422);
        }

        if (booking.getStatus() == BookingStatus.CANCELLED) {
            log.warn("Webhook: booking {} is CANCELLED but payment {} arrived — refunding immediately",
                    resolvedId, moyasarPaymentId);
            try {
                moyasarClient.refundPayment(moyasarPaymentId, booking.getTotalAmount());
            } catch (MoyasarException e) {
                log.error("Auto-refund failed for late payment on booking {}: {}", resolvedId, e.getMessage());
            }
            transactionTemplate.execute(txStatus -> {
                int rows = bookingRepository.markRefundedLatePayment(resolvedId);
                if (rows == 1) {
                    Booking updated = bookingRepository.findById(resolvedId).orElseThrow();
                    eventPublisher.publishEvent(new BookingRefundedLateEvent(this, updated));
                }
                return null;
            });
            return;
        }

        int[] updatedHolder = {0};
        transactionTemplate.execute(txStatus -> {
            int rows = bookingRepository.confirmIfPending(resolvedId, moyasarPaymentId);
            updatedHolder[0] = rows;
            if (rows == 1) {
                seatRepository.confirmSeat(resolvedId);
                Booking confirmed = bookingRepository.findById(resolvedId).orElseThrow();
                eventPublisher.publishEvent(new BookingConfirmedEvent(this, confirmed));
            }
            return null;
        });

        if (updatedHolder[0] == 0) {
            log.info("Webhook: booking {} was already transitioned (concurrent redirect or webhook)", resolvedId);
        }
    }

    private Booking resolveBooking(String bookingId, String paymentId) {
        if (bookingId != null) {
            Optional<Booking> byId = bookingRepository.findById(bookingId);
            if (byId.isPresent()) return byId.get();
        }
        if (paymentId != null) {
            return bookingRepository.findByPaymentId(paymentId).orElse(null);
        }
        return null;
    }

    @Transactional
    public void releaseBooking(String bookingId) {
        releaseBookingInternal(bookingId);
    }

    private void releaseBookingInternal(String bookingId) {
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null || booking.getStatus() != BookingStatus.PENDING) return;
        bookingRepository.updateStatus(bookingId, BookingStatus.CANCELLED, null);
        seatRepository.releaseSeat(bookingId);
    }

    /**
     * Crash-safe refund: CONFIRMED → REFUND_PENDING (seats stay held), refund via Moyasar
     * outside any DB transaction, then REFUND_PENDING → CANCELLED. If the process dies
     * after Moyasar succeeds, {@link #reconcilePendingRefunds()} finishes the job.
     */
    public void cancelBooking(String bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));

        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return;
        }
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new IllegalStateException("Only CONFIRMED bookings can be cancelled. Current: " + booking.getStatus());
        }

        int claimed = transactionTemplate.execute(s -> bookingRepository.markRefundPending(bookingId));
        if (claimed == 0) {
            log.info("Booking {} is already being refunded or cancelled (concurrent request)", bookingId);
            return;
        }

        if (booking.getMoyasarPaymentId() != null) {
            try {
                moyasarClient.refundPayment(booking.getMoyasarPaymentId(), booking.getTotalAmount());
            } catch (MoyasarException e) {
                if (isAlreadyRefunded(booking.getMoyasarPaymentId())) {
                    log.info("Booking {} payment {} was already refunded at Moyasar — proceeding to cancel",
                            bookingId, booking.getMoyasarPaymentId());
                } else {
                    log.warn("Refund failed for booking {} — reverting to CONFIRMED: {}", bookingId, e.getMessage());
                    transactionTemplate.execute(s -> bookingRepository.revertRefundPendingToConfirmed(bookingId));
                    throw e;
                }
            }
        }

        final Booking bookingSnapshot = booking;
        transactionTemplate.execute(txStatus -> {
            int rows = bookingRepository.cancelIfRefundPending(bookingId);
            if (rows == 1) {
                seatRepository.releaseSeat(bookingId);
                eventPublisher.publishEvent(new BookingCancelledEvent(this, bookingSnapshot));
            }
            return null;
        });
    }

    private boolean isAlreadyRefunded(String moyasarPaymentId) {
        try {
            return moyasarClient.getPayment(moyasarPaymentId).isRefunded();
        } catch (MoyasarException e) {
            return false;
        }
    }

    public Optional<Booking> findById(String id) {
        return bookingRepository.findById(id);
    }

    public List<Booking> findByUserId(Long userId) {
        return bookingRepository.findByUserId(userId);
    }

    public List<Booking> findAll(BookingStatus status, Long movieId, int page, int size) {
        return bookingRepository.findAll(status, movieId, page, size);
    }

    public long countAll(BookingStatus status, Long movieId) {
        return bookingRepository.countAll(status, movieId);
    }

    @Scheduled(fixedDelay = 300_000)
    public void releaseExpiredHolds() {
        int holdMinutes = appProperties.getBooking().seatHoldMinutes();
        List<Booking> expired = bookingRepository.findExpiredPendingBookings(holdMinutes);

        for (Booking b : expired) {
            try {
                syncExpiredBooking(b);
            } catch (Exception e) {
                log.error("Failed to sync expired booking {}: {}", b.getId(), e.getMessage());
            }
        }
    }

    private void syncExpiredBooking(Booking booking) {
        String bookingId = booking.getId();

        if (booking.getMoyasarPaymentId() != null) {
            MoyasarPaymentResponse payment;
            try {
                payment = moyasarClient.getPayment(booking.getMoyasarPaymentId());
            } catch (MoyasarException e) {
                log.warn("Cron: could not fetch Moyasar status for booking {} — skipping: {}", bookingId, e.getMessage());
                return;
            }

            if (payment.isPaid()) {
                // Customer may have paid even if both redirect and webhook failed.
                final String paymentId = payment.getId();
                log.info("Cron: booking {} has a successful payment {} — confirming", bookingId, paymentId);
                long expectedHalala = booking.getTotalAmount().multiply(BigDecimal.valueOf(100)).longValue();
                Long paidHalala = payment.getAmount();
                if (paidHalala != null && !paidHalala.equals(expectedHalala)) {
                    log.error("Cron: amount mismatch for booking {} — skipping confirmation", bookingId);
                } else {
                    transactionTemplate.execute(txStatus -> {
                        int rows = bookingRepository.confirmIfPending(bookingId, paymentId);
                        if (rows == 1) {
                            seatRepository.confirmSeat(bookingId);
                            Booking confirmed = bookingRepository.findById(bookingId).orElseThrow();
                            eventPublisher.publishEvent(new BookingConfirmedEvent(this, confirmed));
                            log.info("Cron: booking {} confirmed via payment recovery", bookingId);
                        }
                        return null;
                    });
                }
                return;
            }
        }

        transactionTemplate.execute(txStatus -> {
            Booking fresh = bookingRepository.findById(bookingId).orElse(null);
            if (fresh == null || fresh.getStatus() != BookingStatus.PENDING) return null;

            log.info("Cron: cancelling expired booking {}", bookingId);
            bookingRepository.updateStatus(bookingId, BookingStatus.CANCELLED, null);
            seatRepository.releaseSeat(bookingId);
            return null;
        });
    }

    private static final int REFUND_PENDING_GRACE_MINUTES = 2;

    @Scheduled(fixedDelay = 300_000)
    public void reconcilePendingRefunds() {
        List<Booking> stuck = bookingRepository.findStuckRefundPendingBookings(REFUND_PENDING_GRACE_MINUTES);

        for (Booking b : stuck) {
            try {
                reconcileRefund(b);
            } catch (Exception e) {
                log.error("Failed to reconcile REFUND_PENDING booking {}: {}", b.getId(), e.getMessage());
            }
        }
    }

    private void reconcileRefund(Booking booking) {
        String bookingId = booking.getId();

        if (booking.getMoyasarPaymentId() == null) {
            log.warn("Reconcile: booking {} stuck in REFUND_PENDING with no Moyasar id — cancelling", bookingId);
            finalizeRefundCancellation(booking);
            return;
        }

        MoyasarPaymentResponse payment;
        try {
            payment = moyasarClient.getPayment(booking.getMoyasarPaymentId());
        } catch (MoyasarException e) {
            log.warn("Reconcile: could not fetch Moyasar status for booking {} — retrying next cycle: {}",
                    bookingId, e.getMessage());
            return;
        }

        if (payment.isRefunded()) {
            log.info("Reconcile: booking {} payment {} is refunded at Moyasar — finalizing cancellation",
                    bookingId, booking.getMoyasarPaymentId());
            finalizeRefundCancellation(booking);
        } else {
            log.info("Reconcile: booking {} payment {} is NOT refunded (status={}) — reverting to CONFIRMED",
                    bookingId, booking.getMoyasarPaymentId(), payment.getStatus());
            transactionTemplate.execute(s -> bookingRepository.revertRefundPendingToConfirmed(bookingId));
        }
    }

    private void finalizeRefundCancellation(Booking booking) {
        String bookingId = booking.getId();
        transactionTemplate.execute(txStatus -> {
            int rows = bookingRepository.cancelIfRefundPending(bookingId);
            if (rows == 1) {
                seatRepository.releaseSeat(bookingId);
                eventPublisher.publishEvent(new BookingCancelledEvent(this, booking));
            }
            return null;
        });
    }

    public static class SeatUnavailableException extends RuntimeException {
        public SeatUnavailableException(String message) { super(message); }
    }
}
