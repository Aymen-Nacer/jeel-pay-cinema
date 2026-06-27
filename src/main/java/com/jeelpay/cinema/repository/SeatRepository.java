package com.jeelpay.cinema.repository;

import com.jeelpay.cinema.domain.Seat;
import com.jeelpay.cinema.domain.SeatType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Access to the physical seat catalog (one row per seat per hall, shared by
 * every showtime in that hall).
 */
@Repository
public class SeatRepository {

    private final NamedParameterJdbcTemplate jdbc;

    static final RowMapper<Seat> ROW_MAPPER = (rs, i) -> {
        Seat s = new Seat();
        s.setId(rs.getLong("id"));
        s.setHallId(rs.getLong("hall_id"));
        s.setRowLabel(rs.getString("row_label"));
        s.setSeatNumber(rs.getInt("seat_number"));
        s.setSeatType(SeatType.valueOf(rs.getString("seat_type")));
        return s;
    };

    public SeatRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Seat> findByHallId(Long hallId) {
        var params = new MapSqlParameterSource("hallId", hallId);
        return jdbc.query(
                "SELECT * FROM seats WHERE hall_id = :hallId ORDER BY row_label, seat_number",
                params, ROW_MAPPER);
    }
}
