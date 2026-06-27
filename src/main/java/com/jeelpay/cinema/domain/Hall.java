package com.jeelpay.cinema.domain;

public class Hall {
    private Long id;
    private String name;
    private int totalRows;
    private int seatsPerRow;

    public Hall() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int totalRows) { this.totalRows = totalRows; }
    public int getSeatsPerRow() { return seatsPerRow; }
    public void setSeatsPerRow(int seatsPerRow) { this.seatsPerRow = seatsPerRow; }

    public int getTotalSeats() {
        return totalRows * seatsPerRow;
    }
}
