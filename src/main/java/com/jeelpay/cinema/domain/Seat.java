package com.jeelpay.cinema.domain;

/**
 * A static, physical seat in a hall. Defined once per hall and shared by every
 * showtime in that hall. Booking status per screening lives in {@link ShowtimeSeat}.
 */
public class Seat {
    private Long id;
    private Long hallId;
    private String rowLabel;
    private int seatNumber;
    private SeatType seatType;

    public Seat() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getHallId() { return hallId; }
    public void setHallId(Long hallId) { this.hallId = hallId; }
    public String getRowLabel() { return rowLabel; }
    public void setRowLabel(String rowLabel) { this.rowLabel = rowLabel; }
    public int getSeatNumber() { return seatNumber; }
    public void setSeatNumber(int seatNumber) { this.seatNumber = seatNumber; }
    public SeatType getSeatType() { return seatType; }
    public void setSeatType(SeatType seatType) { this.seatType = seatType; }

    /** Human-readable label, e.g. "A1". */
    public String getLabel() {
        return rowLabel + seatNumber;
    }
}
