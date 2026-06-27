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

CALL generate_seats_for_hall(1);
CALL generate_seats_for_hall(2);

INSERT INTO showtime_seats (showtime_id, seat_id, status)
SELECT st.id, s.id, 'AVAILABLE'
FROM   showtimes st
JOIN   seats s ON s.hall_id = st.hall_id;
