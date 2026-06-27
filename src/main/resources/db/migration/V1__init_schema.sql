CREATE TABLE users (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(50)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE movies (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tmdb_id         BIGINT UNIQUE,
    title           VARCHAR(255) NOT NULL,
    synopsis        TEXT,
    poster_url      VARCHAR(255),
    runtime_minutes INT,
    genres          VARCHAR(255),
    release_year    INT,
    rating          DECIMAL(3,1)
);

CREATE TABLE halls (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    total_rows    INT          NOT NULL,
    seats_per_row INT          NOT NULL
);

CREATE TABLE seats (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    hall_id     BIGINT       NOT NULL,
    row_label   VARCHAR(5)   NOT NULL,
    seat_number INT          NOT NULL,
    seat_type   VARCHAR(20)  NOT NULL DEFAULT 'STANDARD',
    UNIQUE KEY uk_hall_seat (hall_id, row_label, seat_number),
    FOREIGN KEY (hall_id) REFERENCES halls(id)
);

CREATE TABLE showtimes (
    id         BIGINT         AUTO_INCREMENT PRIMARY KEY,
    movie_id   BIGINT         NOT NULL,
    hall_id    BIGINT         NOT NULL,
    start_time DATETIME       NOT NULL,
    price      DECIMAL(10,2)  NOT NULL,
    FOREIGN KEY (movie_id) REFERENCES movies(id),
    FOREIGN KEY (hall_id)  REFERENCES halls(id)
);

CREATE TABLE bookings (
    id                  VARCHAR(36)   NOT NULL PRIMARY KEY,
    user_id             BIGINT        NOT NULL,
    showtime_id         BIGINT        NOT NULL,
    seat_id             BIGINT,
    status              VARCHAR(50)   NOT NULL,
    total_amount        DECIMAL(10,2) NOT NULL,
    moyasar_payment_id  VARCHAR(255),
    reminder_sent       BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_moyasar_payment_id UNIQUE (moyasar_payment_id),
    FOREIGN KEY (user_id)     REFERENCES users(id),
    FOREIGN KEY (showtime_id) REFERENCES showtimes(id),
    FOREIGN KEY (seat_id)     REFERENCES seats(id)
);

CREATE INDEX idx_bookings_status_updated_at ON bookings (status, updated_at);

CREATE TABLE booking_seats (
    id         BIGINT      AUTO_INCREMENT PRIMARY KEY,
    booking_id VARCHAR(36) NOT NULL,
    seat_id    BIGINT      NOT NULL,
    UNIQUE KEY uk_booking_seat (booking_id, seat_id),
    FOREIGN KEY (booking_id) REFERENCES bookings(id),
    FOREIGN KEY (seat_id)    REFERENCES seats(id)
);

CREATE TABLE showtime_seats (
    id          BIGINT      AUTO_INCREMENT PRIMARY KEY,
    showtime_id BIGINT      NOT NULL,
    seat_id     BIGINT      NOT NULL,
    status      VARCHAR(50) NOT NULL DEFAULT 'AVAILABLE',
    booking_id  VARCHAR(36),
    UNIQUE KEY uk_showtime_seat (showtime_id, seat_id),
    FOREIGN KEY (showtime_id) REFERENCES showtimes(id),
    FOREIGN KEY (seat_id)     REFERENCES seats(id),
    CONSTRAINT fk_showtime_seats_booking FOREIGN KEY (booking_id) REFERENCES bookings(id)
);
