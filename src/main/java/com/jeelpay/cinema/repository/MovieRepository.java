package com.jeelpay.cinema.repository;

import com.jeelpay.cinema.domain.Movie;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MovieRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public static final RowMapper<Movie> ROW_MAPPER = (rs, i) -> {
        Movie m = new Movie();
        m.setId(rs.getLong("id"));
        m.setTmdbId(rs.getLong("tmdb_id"));
        m.setTitle(rs.getString("title"));
        m.setSynopsis(rs.getString("synopsis"));
        m.setPosterUrl(rs.getString("poster_url"));
        m.setRuntimeMinutes(rs.getInt("runtime_minutes"));
        m.setGenres(rs.getString("genres"));
        m.setReleaseYear(rs.getInt("release_year"));
        m.setRating(rs.getBigDecimal("rating"));
        return m;
    };

    public MovieRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Movie> findAll() {
        return jdbc.query("SELECT * FROM movies ORDER BY rating DESC", ROW_MAPPER);
    }

    public Optional<Movie> findById(Long id) {
        var params = new MapSqlParameterSource("id", id);
        var list = jdbc.query("SELECT * FROM movies WHERE id = :id", params, ROW_MAPPER);
        return list.stream().findFirst();
    }
}
