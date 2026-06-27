package com.jeelpay.cinema.repository;

import com.jeelpay.cinema.domain.Booking;
import com.jeelpay.cinema.domain.BookingStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class BookingRepository {

    private final NamedParameterJdbcTemplate jdbc;

    private static final RowMapper<Booking> ROW_MAPPER = (rs, i) -> {
        Booking b = new Booking();
        b.setId(rs.getString("id"));
        b.setUserId(rs.getLong("user_id"));
        b.setShowtimeId(rs.getLong("showtime_id"));
        long seatId = rs.getLong("seat_id");
        b.setSeatId(rs.wasNull() ? null : seatId);
        b.setStatus(BookingStatus.valueOf(rs.getString("status")));
        b.setTotalAmount(rs.getBigDecimal("total_amount"));
        b.setMoyasarPaymentId(rs.getString("moyasar_payment_id"));
        b.setReminderSent(rs.getBoolean("reminder_sent"));
        b.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return b;
    };

    private static final RowMapper<Booking> FULL_ROW_MAPPER = (rs, i) -> {
        Booking b = ROW_MAPPER.mapRow(rs, i);
        b.setSeatNumber(rs.getString("seat_labels"));
        b.setMovieTitle(rs.getString("movie_title"));
        b.setHallName(rs.getString("hall_name"));
        b.setShowtimeStart(rs.getTimestamp("showtime_start").toLocalDateTime());
        b.setUserEmail(rs.getString("user_email"));
        return b;
    };

    /**
     * Aggregates all seat labels for the booking via GROUP_CONCAT so that
     * multi-seat bookings are loaded in a single query.
     */
    private static final String FULL_SELECT = """
            SELECT b.*,
                   GROUP_CONCAT(s.row_label, s.seat_number ORDER BY s.row_label, s.seat_number SEPARATOR ', ') AS seat_labels,
                   m.title       AS movie_title,
                   h.name        AS hall_name,
                   st.start_time AS showtime_start,
                   u.email       AS user_email
            FROM bookings b
            JOIN showtimes st ON b.showtime_id = st.id
            JOIN movies m     ON st.movie_id = m.id
            JOIN halls h      ON st.hall_id = h.id
            JOIN users u      ON b.user_id = u.id
            LEFT JOIN booking_seats bs ON bs.booking_id = b.id
            LEFT JOIN seats s          ON s.id = bs.seat_id
            """;

    private static final String FULL_GROUP_BY = """
             GROUP BY b.id, b.user_id, b.showtime_id, b.seat_id, b.status,
                      b.total_amount, b.moyasar_payment_id, b.reminder_sent,
                      b.created_at, b.updated_at,
                      m.title, h.name, st.start_time, u.email
            """;

    public BookingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert a new booking with a server-generated UUID primary key and link
     * its seats in the booking_seats junction table.
     */
    public Booking save(Booking booking) {
        String id = UUID.randomUUID().toString();
        booking.setId(id);

        // Use the first seatId as the legacy seat_id column (nullable for old rows).
        Long primarySeatId = !booking.getSeatIds().isEmpty() ? booking.getSeatIds().get(0) : booking.getSeatId();
        booking.setSeatId(primarySeatId);

        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", booking.getUserId())
                .addValue("showtimeId", booking.getShowtimeId())
                .addValue("seatId", primarySeatId)
                .addValue("status", booking.getStatus().name())
                .addValue("totalAmount", booking.getTotalAmount());

        jdbc.update("""
                INSERT INTO bookings (id, user_id, showtime_id, seat_id, status, total_amount)
                VALUES (:id, :userId, :showtimeId, :seatId, :status, :totalAmount)
                """, params);

        // Insert all seats into the junction table.
        for (Long seatId : booking.getSeatIds()) {
            var sp = new MapSqlParameterSource()
                    .addValue("bookingId", id)
                    .addValue("seatId", seatId);
            jdbc.update("INSERT INTO booking_seats (booking_id, seat_id) VALUES (:bookingId, :seatId)", sp);
        }

        return booking;
    }

    public Optional<Booking> findById(String id) {
        var params = new MapSqlParameterSource("id", id);
        var list = jdbc.query(
                FULL_SELECT + " WHERE b.id = :id" + FULL_GROUP_BY,
                params, FULL_ROW_MAPPER);
        Optional<Booking> result = list.stream().findFirst();
        result.ifPresent(b -> b.setSeatIds(findSeatIdsByBookingId(id)));
        return result;
    }

    public List<Booking> findByUserId(Long userId) {
        var params = new MapSqlParameterSource("userId", userId);
        List<Booking> bookings = jdbc.query(
                FULL_SELECT + " WHERE b.user_id = :userId" + FULL_GROUP_BY + " ORDER BY b.created_at DESC",
                params, FULL_ROW_MAPPER);
        bookings.forEach(b -> b.setSeatIds(findSeatIdsByBookingId(b.getId())));
        return bookings;
    }

    public List<Booking> findAll(BookingStatus status, Long movieId, int page, int size) {
        var params = new MapSqlParameterSource()
                .addValue("offset", (long) page * size)
                .addValue("limit", size);

        var where = new StringBuilder(" WHERE 1=1");
        if (status != null) {
            where.append(" AND b.status = :status");
            params.addValue("status", status.name());
        }
        if (movieId != null) {
            where.append(" AND m.id = :movieId");
            params.addValue("movieId", movieId);
        }

        List<Booking> bookings = jdbc.query(
                FULL_SELECT + where + FULL_GROUP_BY + " ORDER BY b.created_at DESC LIMIT :limit OFFSET :offset",
                params, FULL_ROW_MAPPER);
        bookings.forEach(b -> b.setSeatIds(findSeatIdsByBookingId(b.getId())));
        return bookings;
    }

    public long countAll(BookingStatus status, Long movieId) {
        var params = new MapSqlParameterSource();
        var where = new StringBuilder("""
                SELECT COUNT(*) FROM bookings b
                JOIN showtimes st ON b.showtime_id = st.id
                JOIN movies m ON st.movie_id = m.id
                WHERE 1=1
                """);
        if (status != null) {
            where.append(" AND b.status = :status");
            params.addValue("status", status.name());
        }
        if (movieId != null) {
            where.append(" AND m.id = :movieId");
            params.addValue("movieId", movieId);
        }
        var count = jdbc.queryForObject(where.toString(), params, Long.class);
        return count != null ? count : 0;
    }

    public void updateStatus(String id, BookingStatus status, String moyasarPaymentId) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status.name())
                .addValue("moyasarPaymentId", moyasarPaymentId);
        jdbc.update(
                "UPDATE bookings SET status = :status, moyasar_payment_id = :moyasarPaymentId WHERE id = :id",
                params);
    }

    /**
     * Atomically save the Moyasar payment ID and transition the booking from
     * PENDING to CONFIRMED. Returns the number of rows updated (1 = success, 0 = already transitioned).
     */
    public int confirmIfPending(String id, String moyasarPaymentId) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("paymentId", moyasarPaymentId);
        return jdbc.update(
                "UPDATE bookings SET status = 'CONFIRMED', moyasar_payment_id = :paymentId WHERE id = :id AND status = 'PENDING'",
                params);
    }

    /**
     * Step 1 of the refund workflow: atomically move CONFIRMED → REFUND_PENDING.
     * Fast, safe, and does NOT release the seats yet. Returns 1 if the row was
     * claimed for refunding, 0 if it was already in another state.
     */
    public int markRefundPending(String id) {
        var params = new MapSqlParameterSource("id", id);
        return jdbc.update(
                "UPDATE bookings SET status = 'REFUND_PENDING' WHERE id = :id AND status = 'CONFIRMED'",
                params);
    }

    /**
     * Step 3 (success) of the refund workflow: atomically move REFUND_PENDING → CANCELLED.
     * Returns 1 if updated, 0 if the row was no longer REFUND_PENDING.
     */
    public int cancelIfRefundPending(String id) {
        var params = new MapSqlParameterSource("id", id);
        return jdbc.update(
                "UPDATE bookings SET status = 'CANCELLED' WHERE id = :id AND status = 'REFUND_PENDING'",
                params);
    }

    /**
     * Step 3 (failure) of the refund workflow: the money never left, so revert
     * REFUND_PENDING → CONFIRMED. Returns 1 if reverted, 0 otherwise.
     */
    public int revertRefundPendingToConfirmed(String id) {
        var params = new MapSqlParameterSource("id", id);
        return jdbc.update(
                "UPDATE bookings SET status = 'CONFIRMED' WHERE id = :id AND status = 'REFUND_PENDING'",
                params);
    }

    /**
     * Reconciliation: find bookings stuck in REFUND_PENDING for longer than the
     * given grace window (e.g. the DB crashed after Moyasar succeeded).
     */
    public List<Booking> findStuckRefundPendingBookings(int olderThanMinutes) {
        var params = new MapSqlParameterSource("minutes", olderThanMinutes);
        List<Booking> bookings = jdbc.query(FULL_SELECT + """
                WHERE b.status = 'REFUND_PENDING'
                  AND b.updated_at < DATE_SUB(NOW(), INTERVAL :minutes MINUTE)
                """ + FULL_GROUP_BY, params, FULL_ROW_MAPPER);
        bookings.forEach(b -> b.setSeatIds(findSeatIdsByBookingId(b.getId())));
        return bookings;
    }

    /**
     * Save the Moyasar payment ID on a booking (Tx1.5 after payment creation).
     */
    public void saveMoyasarId(String bookingId, String moyasarPaymentId) {
        var params = new MapSqlParameterSource()
                .addValue("id", bookingId)
                .addValue("paymentId", moyasarPaymentId);
        jdbc.update(
                "UPDATE bookings SET moyasar_payment_id = :paymentId WHERE id = :id",
                params);
    }

    /**
     * Atomically mark a CANCELLED booking as REFUNDED_LATE_PAYMENT.
     * Returns 1 if updated, 0 if the booking was already in another state.
     */
    public int markRefundedLatePayment(String id) {
        var params = new MapSqlParameterSource("id", id);
        return jdbc.update(
                "UPDATE bookings SET status = 'REFUNDED_LATE_PAYMENT' WHERE id = :id AND status = 'CANCELLED'",
                params);
    }

    /**
     * Find CONFIRMED bookings for today (Asia/Riyadh) that haven't received a reminder.
     */
    public List<Booking> findConfirmedBookingsForReminderOn(LocalDate date) {
        var params = new MapSqlParameterSource("date", date.toString());
        List<Booking> bookings = jdbc.query(
                FULL_SELECT + """
                WHERE b.status = 'CONFIRMED'
                  AND b.reminder_sent = FALSE
                  AND DATE(CONVERT_TZ(st.start_time, '+00:00', '+03:00')) = :date
                """ + FULL_GROUP_BY, params, FULL_ROW_MAPPER);
        bookings.forEach(b -> b.setSeatIds(findSeatIdsByBookingId(b.getId())));
        return bookings;
    }

    public void markReminderSent(String bookingId) {
        var params = new MapSqlParameterSource("id", bookingId);
        jdbc.update("UPDATE bookings SET reminder_sent = TRUE WHERE id = :id", params);
    }

    /**
     * Find PENDING bookings older than the hold window (for expiry cleanup).
     */
    public List<Booking> findExpiredPendingBookings(int holdMinutes) {
        var params = new MapSqlParameterSource("minutes", holdMinutes);
        List<Booking> bookings = jdbc.query(FULL_SELECT + """
                WHERE b.status = 'PENDING'
                  AND b.created_at < DATE_SUB(NOW(), INTERVAL :minutes MINUTE)
                """ + FULL_GROUP_BY, params, FULL_ROW_MAPPER);
        bookings.forEach(b -> b.setSeatIds(findSeatIdsByBookingId(b.getId())));
        return bookings;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<Long> findSeatIdsByBookingId(String bookingId) {
        var params = new MapSqlParameterSource("bookingId", bookingId);
        return jdbc.query(
                "SELECT seat_id FROM booking_seats WHERE booking_id = :bookingId ORDER BY seat_id",
                params, (rs, i) -> rs.getLong("seat_id"));
    }
}
