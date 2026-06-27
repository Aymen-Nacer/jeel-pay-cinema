package com.jeelpay.cinema.repository;

import com.jeelpay.cinema.domain.User;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class UserRepository {

    private final NamedParameterJdbcTemplate jdbc;

    private static final RowMapper<User> ROW_MAPPER = (rs, i) -> {
        User u = new User();
        u.setId(rs.getLong("id"));
        u.setEmail(rs.getString("email"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setRole(rs.getString("role"));
        u.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return u;
    };

    public UserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<User> findByEmail(String email) {
        var params = new MapSqlParameterSource("email", email);
        var list = jdbc.query(
                "SELECT * FROM users WHERE email = :email", params, ROW_MAPPER);
        return list.stream().findFirst();
    }

    public Optional<User> findById(Long id) {
        var params = new MapSqlParameterSource("id", id);
        var list = jdbc.query(
                "SELECT * FROM users WHERE id = :id", params, ROW_MAPPER);
        return list.stream().findFirst();
    }

    public boolean existsByEmail(String email) {
        var params = new MapSqlParameterSource("email", email);
        var count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = :email", params, Integer.class);
        return count != null && count > 0;
    }

    public User save(User user) {
        var params = new MapSqlParameterSource()
                .addValue("email", user.getEmail())
                .addValue("passwordHash", user.getPasswordHash())
                .addValue("role", user.getRole());
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(
                "INSERT INTO users (email, password_hash, role) VALUES (:email, :passwordHash, :role)",
                params, keyHolder);
        user.setId(keyHolder.getKey().longValue());
        return user;
    }
}
