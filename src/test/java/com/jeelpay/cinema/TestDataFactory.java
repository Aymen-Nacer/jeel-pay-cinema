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

/**
 * Self-contained test-data builder.
 *
 * <h3>Why this class exists — the Mystery Guest problem</h3>
 * Using hardcoded IDs like {@code SHOWTIME_ID = 1L} creates an invisible dependency
 * on Flyway seed data (V3/V4 migrations). Any future change to those scripts —
 * re-ordering inserts, removing a showtime, changing seat layout — silently breaks
 * tests that assume specific IDs exist. This is the "Mystery Guest" anti-pattern:
 * the test's pre-condition lives somewhere the reader cannot see.
 *
 * <h3>Solution</h3>
 * Every test that needs a showtime calls {@link #createShowtimeWithSeats} to build
 * its own movie → hall → showtime → seats → showtime_seats chain inside the test
 * transaction. The test is then fully self-contained: it creates what it needs,
 * uses the returned IDs, and does not rely on any external seed data.
 *
 * <p>This is a Spring {@link Component} so it can be {@code @Autowired} into any
 * integration test. Because it lives in {@code src/test}, it is never included in
 * the production jar.</p>
 */
@Component
public class TestDataFactory {

    private final NamedParameterJdbcTemplate jdbc;

    public TestDataFactory(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Public API ───────────────────────────────────────────────────────────────

    /**
     * Creates an isolated showtime together with all required supporting data:
     * a unique movie, a unique hall, physical seat rows, and per-showtime
     * {@code showtime_seats} rows (all {@code AVAILABLE}).
     *
     * <p>The returned {@link ShowtimeContext} carries the generated showtime ID and
     * the list of seat labels (e.g. {@code ["A1","A2","B1"]}) so tests can reference
     * concrete seats without guessing.</p>
     *
     * @param rows        number of seat rows (row A = 1, row B = 2, …)
     * @param seatsPerRow number of seats per row
     */
    public ShowtimeContext createShowtimeWithSeats(int rows, int seatsPerRow) {
        long movieId    = insertMovie();
        long hallId     = insertHall(rows, seatsPerRow);
        long showtimeId = insertShowtime(movieId, hallId);
        List<String> seatLabels = insertSeatsAndShowtimeSeats(hallId, showtimeId, rows, seatsPerRow);
        return new ShowtimeContext(showtimeId, seatLabels);
    }

    // ── Private insert helpers ───────────────────────────────────────────────────

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

    /**
     * Creates physical {@code seats} rows for the hall and links each one to the
     * showtime via {@code showtime_seats} (status = AVAILABLE, version = 0).
     *
     * Row labels follow the same convention as the stored procedure in V4:
     * row 1 → 'A', row 2 → 'B', etc.
     *
     * @return ordered list of seat labels, e.g. {@code ["A1","A2","B1","B2"]}
     */
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

    // ── Value object ─────────────────────────────────────────────────────────────

    /**
     * Holds the identifiers returned by {@link #createShowtimeWithSeats}.
     *
     * @param showtimeId  the generated showtime ID — use this instead of {@code 1L}
     * @param seatLabels  all seat labels in row-then-column order (e.g. "A1", "A2")
     */
    public record ShowtimeContext(long showtimeId, List<String> seatLabels) {

        /** Returns the first seat label in the hall, e.g. "A1". */
        public String firstSeat() {
            return seatLabels.get(0);
        }

        /** Returns the seat label at a given zero-based index. */
        public String seat(int index) {
            return seatLabels.get(index);
        }
    }
}
