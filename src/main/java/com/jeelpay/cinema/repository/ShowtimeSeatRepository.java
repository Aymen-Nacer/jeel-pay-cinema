package com.jeelpay.cinema.repository;

import com.jeelpay.cinema.domain.SeatStatus;
import com.jeelpay.cinema.domain.SeatType;
import com.jeelpay.cinema.domain.ShowtimeSeat;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ShowtimeSeatRepository {

    private final NamedParameterJdbcTemplate jdbc;

    static final RowMapper<ShowtimeSeat> ROW_MAPPER = (rs, i) -> {
        ShowtimeSeat s = new ShowtimeSeat();
        s.setId(rs.getLong("id"));
        s.setShowtimeId(rs.getLong("showtime_id"));
        s.setSeatId(rs.getLong("seat_id"));
        s.setStatus(SeatStatus.valueOf(rs.getString("status")));
        s.setBookingId(rs.getString("booking_id"));
        s.setRowLabel(rs.getString("row_label"));
        s.setSeatColumn(rs.getInt("seat_number"));
        s.setSeatType(SeatType.valueOf(rs.getString("seat_type")));
        return s;
    };

    private static final String SELECT_WITH_SEAT = """
            SELECT ss.id, ss.showtime_id, ss.seat_id, ss.status, ss.booking_id,
                   s.row_label, s.seat_number, s.seat_type
            FROM showtime_seats ss
            JOIN seats s ON ss.seat_id = s.id
            """;

    public ShowtimeSeatRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ShowtimeSeat> findByShowtimeId(Long showtimeId) {
        var params = new MapSqlParameterSource("showtimeId", showtimeId);
        return jdbc.query(
                SELECT_WITH_SEAT + " WHERE ss.showtime_id = :showtimeId ORDER BY s.row_label, s.seat_number",
                params, ROW_MAPPER);
    }

    public Optional<ShowtimeSeat> lockForUpdate(Long showtimeId, String seatNumber) {
        var params = new MapSqlParameterSource()
                .addValue("showtimeId", showtimeId)
                .addValue("seatNumber", seatNumber);
        var list = jdbc.query(
                SELECT_WITH_SEAT
                        + " WHERE ss.showtime_id = :showtimeId"
                        + " AND CONCAT(s.row_label, s.seat_number) = :seatNumber"
                        + " FOR UPDATE NOWAIT",
                params, ROW_MAPPER);
        return list.stream().findFirst();
    }

    // Single query locks all seats at once to avoid deadlock from inconsistent lock ordering.
    public List<ShowtimeSeat> lockMultipleForUpdate(Long showtimeId, List<String> seatNumbers) {
        var params = new MapSqlParameterSource()
                .addValue("showtimeId", showtimeId)
                .addValue("seatNumbers", seatNumbers);
        return jdbc.query(
                SELECT_WITH_SEAT
                        + " WHERE ss.showtime_id = :showtimeId"
                        + " AND CONCAT(s.row_label, s.seat_number) IN (:seatNumbers)"
                        + " ORDER BY s.row_label, s.seat_number"
                        + " FOR UPDATE NOWAIT",
                params, ROW_MAPPER);
    }

    public void holdSeat(Long showTimeSeatId, String bookingId) {
        var params = new MapSqlParameterSource()
                .addValue("id", showTimeSeatId)
                .addValue("bookingId", bookingId)
                .addValue("status", SeatStatus.HELD.name());
        jdbc.update(
                "UPDATE showtime_seats SET status = :status, booking_id = :bookingId WHERE id = :id",
                params);
    }

    public void holdSeats(List<Long> showTimeSeatIds, String bookingId) {
        var params = new MapSqlParameterSource()
                .addValue("ids", showTimeSeatIds)
                .addValue("bookingId", bookingId)
                .addValue("status", SeatStatus.HELD.name());
        jdbc.update(
                "UPDATE showtime_seats SET status = :status, booking_id = :bookingId WHERE id IN (:ids)",
                params);
    }

    public void confirmSeat(String bookingId) {
        var params = new MapSqlParameterSource()
                .addValue("bookingId", bookingId)
                .addValue("status", SeatStatus.BOOKED.name());
        jdbc.update(
                "UPDATE showtime_seats SET status = :status WHERE booking_id = :bookingId",
                params);
    }

    public void releaseSeat(String bookingId) {
        var params = new MapSqlParameterSource()
                .addValue("bookingId", bookingId)
                .addValue("status", SeatStatus.AVAILABLE.name());
        jdbc.update(
                "UPDATE showtime_seats SET status = :status, booking_id = NULL WHERE booking_id = :bookingId",
                params);
    }
}
