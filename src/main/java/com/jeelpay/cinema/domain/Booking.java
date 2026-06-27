package com.jeelpay.cinema.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class Booking {

    private String id;
    private Long userId;
    private Long showtimeId;
    private Long seatId;           // kept for backward compat; use seatIds for new bookings
    private BookingStatus status;
    private BigDecimal totalAmount;
    private String moyasarPaymentId;
    private boolean reminderSent;
    private LocalDateTime createdAt;

    // Multiple seat IDs for multi-seat bookings
    private List<Long> seatIds = new ArrayList<>();

    // Joined display fields
    private String seatNumber;        // comma-separated labels, e.g. "A1, A2"
    private String movieTitle;
    private String hallName;
    private LocalDateTime showtimeStart;
    private String userEmail;

    public Booking() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getShowtimeId() { return showtimeId; }
    public void setShowtimeId(Long showtimeId) { this.showtimeId = showtimeId; }

    public Long getSeatId() { return seatId; }
    public void setSeatId(Long seatId) { this.seatId = seatId; }

    public List<Long> getSeatIds() { return seatIds; }
    public void setSeatIds(List<Long> seatIds) { this.seatIds = seatIds != null ? seatIds : new ArrayList<>(); }

    public BookingStatus getStatus() { return status; }
    public void setStatus(BookingStatus status) { this.status = status; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public String getMoyasarPaymentId() { return moyasarPaymentId; }
    public void setMoyasarPaymentId(String moyasarPaymentId) { this.moyasarPaymentId = moyasarPaymentId; }

    public boolean isReminderSent() { return reminderSent; }
    public void setReminderSent(boolean reminderSent) { this.reminderSent = reminderSent; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getSeatNumber() { return seatNumber; }
    public void setSeatNumber(String seatNumber) { this.seatNumber = seatNumber; }

    public String getMovieTitle() { return movieTitle; }
    public void setMovieTitle(String movieTitle) { this.movieTitle = movieTitle; }

    public String getHallName() { return hallName; }
    public void setHallName(String hallName) { this.hallName = hallName; }

    public LocalDateTime getShowtimeStart() { return showtimeStart; }
    public void setShowtimeStart(LocalDateTime showtimeStart) { this.showtimeStart = showtimeStart; }

    public String getUserEmail() { return userEmail; }
    public void setUserEmail(String userEmail) { this.userEmail = userEmail; }

    /** Number of seats in this booking. */
    public int getSeatCount() { return seatIds.isEmpty() ? (seatId != null ? 1 : 0) : seatIds.size(); }
}
