-- V4: Seat generation via stored procedure + showtime_seats population.
--
-- The stored procedure `generate_seats_for_hall` derives every seat row from a
-- hall's total_rows and seats_per_row columns.  All seats are STANDARD type.
-- It is idempotent: calling it again for the same hall wipes and regenerates seats.
--
-- showtime_seats are generated last, after all seats exist for all halls.

-- ── Stored procedure ──────────────────────────────────────────────────────────
DROP PROCEDURE IF EXISTS generate_seats_for_hall;

CREATE PROCEDURE generate_seats_for_hall(IN p_hall_id BIGINT)
BEGIN
    DECLARE v_total_rows    INT;
    DECLARE v_seats_per_row INT;
    DECLARE r               INT DEFAULT 1;
    DECLARE c               INT DEFAULT 1;

    SELECT total_rows, seats_per_row
      INTO v_total_rows, v_seats_per_row
      FROM halls
     WHERE id = p_hall_id;

    -- Idempotent: clear any existing physical seats for this hall first.
    DELETE FROM seats WHERE hall_id = p_hall_id;

    SET r = 1;
    WHILE r <= v_total_rows DO
        SET c = 1;
        WHILE c <= v_seats_per_row DO
            INSERT INTO seats (hall_id, row_label, seat_number, seat_type)
            VALUES (p_hall_id, CHAR(64 + r), c, 'STANDARD');
            SET c = c + 1;
        END WHILE;
        SET r = r + 1;
    END WHILE;
END;

-- ── Generate physical seats for every hall ────────────────────────────────────
CALL generate_seats_for_hall(1);   -- Hall 1: A1–D8  (32 seats)
CALL generate_seats_for_hall(2);   -- Hall 2: A1–E10 (50 seats)

-- ── Populate showtime_seats from the physical seat catalog ────────────────────
-- One row per (showtime, physical seat) pair, all starting AVAILABLE.
INSERT INTO showtime_seats (showtime_id, seat_id, status, version)
SELECT st.id, s.id, 'AVAILABLE', 0
FROM   showtimes st
JOIN   seats s ON s.hall_id = st.hall_id;
