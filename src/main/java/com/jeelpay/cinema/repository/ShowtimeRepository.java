package com.jeelpay.cinema.repository;

import com.jeelpay.cinema.domain.Showtime;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ShowtimeRepository {

    private final NamedParameterJdbcTemplate jdbc;

    private static final RowMapper<Showtime> ROW_MAPPER = (rs, i) -> {
        Showtime st = new Showtime();
        st.setId(rs.getLong("id"));
        st.setMovieId(rs.getLong("movie_id"));
        st.setHallId(rs.getLong("hall_id"));
        st.setStartTime(rs.getTimestamp("start_time").toLocalDateTime());
        st.setPrice(rs.getBigDecimal("price"));
        return st;
    };

    private static final RowMapper<Showtime> FULL_ROW_MAPPER = (rs, i) -> {
        Showtime st = ROW_MAPPER.mapRow(rs, i);
        st.setMovieTitle(rs.getString("movie_title"));
        st.setHallName(rs.getString("hall_name"));
        st.setAvailableSeats(rs.getInt("available_seats"));
        st.setTotalSeats(rs.getInt("total_seats"));
        return st;
    };

    public ShowtimeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Showtime> findByMovieId(Long movieId) {
        var params = new MapSqlParameterSource("movieId", movieId);
        return jdbc.query("""
                SELECT st.*, m.title AS movie_title, h.name AS hall_name,
                       COUNT(CASE WHEN ss.status = 'AVAILABLE' THEN 1 END) AS available_seats,
                       COUNT(ss.id) AS total_seats
                FROM showtimes st
                JOIN movies m ON st.movie_id = m.id
                JOIN halls h ON st.hall_id = h.id
                LEFT JOIN showtime_seats ss ON ss.showtime_id = st.id
                WHERE st.movie_id = :movieId
                GROUP BY st.id, m.title, h.name
                ORDER BY st.start_time
                """, params, FULL_ROW_MAPPER);
    }

    public Optional<Showtime> findById(Long id) {
        var params = new MapSqlParameterSource("id", id);
        var list = jdbc.query("""
                SELECT st.*, m.title AS movie_title, h.name AS hall_name,
                       COUNT(CASE WHEN ss.status = 'AVAILABLE' THEN 1 END) AS available_seats,
                       COUNT(ss.id) AS total_seats
                FROM showtimes st
                JOIN movies m ON st.movie_id = m.id
                JOIN halls h ON st.hall_id = h.id
                LEFT JOIN showtime_seats ss ON ss.showtime_id = st.id
                WHERE st.id = :id
                GROUP BY st.id, m.title, h.name
                """, params, FULL_ROW_MAPPER);
        return list.stream().findFirst();
    }

    public Optional<Showtime> findByIdBasic(Long id) {
        var params = new MapSqlParameterSource("id", id);
        var list = jdbc.query("SELECT * FROM showtimes WHERE id = :id", params, ROW_MAPPER);
        return list.stream().findFirst();
    }
}
