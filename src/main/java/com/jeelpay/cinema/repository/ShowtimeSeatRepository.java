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
        s.setVersion(rs.getInt("version"));
        s.setRowLabel(rs.getString("row_label"));
        s.setSeatColumn(rs.getInt("seat_number"));
        s.setSeatType(SeatType.valueOf(rs.getString("seat_type")));
        return s;
    };

    /** Selects per-showtime state joined with the physical seat catalog. */
    private static final String SELECT_WITH_SEAT = """
            SELECT ss.id, ss.showtime_id, ss.seat_id, ss.status, ss.booking_id, ss.version,
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

    /**
     * Acquire a pessimistic write lock on the showtime_seat row for the given seat label
     * (e.g. "A1") within the current transaction. NOWAIT means concurrent requests fail
     * fast rather than queuing.
     */
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

    /**
     * Acquire pessimistic write locks on multiple seat rows at once for the given seat labels.
     * Locks are acquired in a single query to avoid deadlock risk from ordering.
     */
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
                "UPDATE showtime_seats SET status = :status, booking_id = :bookingId, version = version + 1 WHERE id = :id",
                params);
    }

    /** Hold all given showtime_seat rows in a single UPDATE. */
    public void holdSeats(List<Long> showTimeSeatIds, String bookingId) {
        var params = new MapSqlParameterSource()
                .addValue("ids", showTimeSeatIds)
                .addValue("bookingId", bookingId)
                .addValue("status", SeatStatus.HELD.name());
        jdbc.update(
                "UPDATE showtime_seats SET status = :status, booking_id = :bookingId, version = version + 1 WHERE id IN (:ids)",
                params);
    }

    public void confirmSeat(String bookingId) {
        var params = new MapSqlParameterSource()
                .addValue("bookingId", bookingId)
                .addValue("status", SeatStatus.BOOKED.name());
        jdbc.update(
                "UPDATE showtime_seats SET status = :status, version = version + 1 WHERE booking_id = :bookingId",
                params);
    }

    public void releaseSeat(String bookingId) {
        var params = new MapSqlParameterSource()
                .addValue("bookingId", bookingId)
                .addValue("status", SeatStatus.AVAILABLE.name());
        jdbc.update(
                "UPDATE showtime_seats SET status = :status, booking_id = NULL, version = version + 1 WHERE booking_id = :bookingId",
                params);
    }
}
