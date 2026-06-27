package com.jeelpay.cinema;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Builds isolated showtime data in tests instead of relying on Flyway seed IDs. */
@Component
public class TestDataFactory {

    private final NamedParameterJdbcTemplate jdbc;

    public TestDataFactory(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ShowtimeContext createShowtimeWithSeats(int rows, int seatsPerRow) {
        long movieId    = insertMovie();
        long hallId     = insertHall(rows, seatsPerRow);
        long showtimeId = insertShowtime(movieId, hallId);
        List<String> seatLabels = insertSeatsAndShowtimeSeats(hallId, showtimeId, rows, seatsPerRow);
        return new ShowtimeContext(showtimeId, seatLabels);
    }

    private long insertMovie() {
        var params = new MapSqlParameterSource()
                .addValue("tmdbId",         ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999))
                .addValue("title",          "Test Movie " + UUID.randomUUID().toString().substring(0, 8))
                .addValue("synopsis",       "Auto-generated movie for integration tests")
                .addValue("posterUrl",      "https://example.com/test-poster.jpg")
                .addValue("runtimeMinutes", 120)
                .addValue("genres",         "Drama")
                .addValue("releaseYear",    2024)
                .addValue("rating",         new BigDecimal("7.5"));
        var key = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO movies
                    (tmdb_id, title, synopsis, poster_url, runtime_minutes, genres, release_year, rating)
                VALUES
                    (:tmdbId, :title, :synopsis, :posterUrl, :runtimeMinutes, :genres, :releaseYear, :rating)
                """, params, key);
        return key.getKey().longValue();
    }

    private long insertHall(int rows, int seatsPerRow) {
        var params = new MapSqlParameterSource()
                .addValue("name",        "Test Hall " + UUID.randomUUID().toString().substring(0, 8))
                .addValue("totalRows",   rows)
                .addValue("seatsPerRow", seatsPerRow);
        var key = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO halls (name, total_rows, seats_per_row)
                VALUES (:name, :totalRows, :seatsPerRow)
                """, params, key);
        return key.getKey().longValue();
    }

    private long insertShowtime(long movieId, long hallId) {
        var params = new MapSqlParameterSource()
                .addValue("movieId",   movieId)
                .addValue("hallId",    hallId)
                .addValue("startTime", LocalDateTime.now().plusDays(30))
                .addValue("price",     new BigDecimal("45.00"));
        var key = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO showtimes (movie_id, hall_id, start_time, price)
                VALUES (:movieId, :hallId, :startTime, :price)
                """, params, key);
        return key.getKey().longValue();
    }

    private List<String> insertSeatsAndShowtimeSeats(
            long hallId, long showtimeId, int rows, int seatsPerRow) {

        List<String> labels = new ArrayList<>(rows * seatsPerRow);

        for (int r = 1; r <= rows; r++) {
            String rowLabel = String.valueOf((char) ('A' + r - 1));

            for (int c = 1; c <= seatsPerRow; c++) {

                var seatParams = new MapSqlParameterSource()
                        .addValue("hallId",     hallId)
                        .addValue("rowLabel",   rowLabel)
                        .addValue("seatNumber", c)
                        .addValue("seatType",   "STANDARD");
                var seatKey = new GeneratedKeyHolder();
                jdbc.update("""
                        INSERT INTO seats (hall_id, row_label, seat_number, seat_type)
                        VALUES (:hallId, :rowLabel, :seatNumber, :seatType)
                        """, seatParams, seatKey);
                long seatId = seatKey.getKey().longValue();

                var ssParams = new MapSqlParameterSource()
                        .addValue("showtimeId", showtimeId)
                        .addValue("seatId",     seatId)
                        .addValue("status",     "AVAILABLE")
                        .addValue("version",    0);
                jdbc.update("""
                        INSERT INTO showtime_seats (showtime_id, seat_id, status, version)
                        VALUES (:showtimeId, :seatId, :status, :version)
                        """, ssParams);

                labels.add(rowLabel + c);
            }
        }
        return labels;
    }

    public record ShowtimeContext(long showtimeId, List<String> seatLabels) {

        public String firstSeat() {
            return seatLabels.get(0);
        }

        public String seat(int index) {
            return seatLabels.get(index);
        }
    }
}
