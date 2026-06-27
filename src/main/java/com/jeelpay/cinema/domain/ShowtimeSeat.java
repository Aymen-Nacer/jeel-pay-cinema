package com.jeelpay.cinema.domain;

/**
 * Per-showtime runtime state of a seat: whether it is available, held, or booked
 * for a specific screening. The physical seat identity (label, type, hall) lives in
 * {@link Seat}; this row only references it via {@code seatId} and carries joined
 * display fields populated by the repository.
 */
public class ShowtimeSeat {

    private Long id;
    private Long showtimeId;
    private Long seatId;
    private SeatStatus status;
    private String bookingId;
    private int version;

    // Joined from the physical seats catalog for display.
    private String rowLabel;
    private int seatColumn;
    private SeatType seatType;

    public ShowtimeSeat() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getShowtimeId() { return showtimeId; }
    public void setShowtimeId(Long showtimeId) { this.showtimeId = showtimeId; }

    public Long getSeatId() { return seatId; }
    public void setSeatId(Long seatId) { this.seatId = seatId; }

    public SeatStatus getStatus() { return status; }
    public void setStatus(SeatStatus status) { this.status = status; }

    public String getBookingId() { return bookingId; }
    public void setBookingId(String bookingId) { this.bookingId = bookingId; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getRowLabel() { return rowLabel; }
    public void setRowLabel(String rowLabel) { this.rowLabel = rowLabel; }

    public int getSeatColumn() { return seatColumn; }
    public void setSeatColumn(int seatColumn) { this.seatColumn = seatColumn; }

    public SeatType getSeatType() { return seatType; }
    public void setSeatType(SeatType seatType) { this.seatType = seatType; }

    /** Human-readable seat label, e.g. "A1". */
    public String getSeatNumber() {
        return (rowLabel != null ? rowLabel : "") + seatColumn;
    }

    public boolean isAvailable()  { return status == SeatStatus.AVAILABLE; }
    public boolean isHeld()       { return status == SeatStatus.HELD; }
    public boolean isBooked()     { return status == SeatStatus.BOOKED; }

    public boolean isVip()        { return false; }
    public boolean isAccessible() { return false; }
}
