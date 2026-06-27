package com.jeelpay.cinema;

import com.jeelpay.cinema.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationTest extends AbstractIntegrationTest {

    @Autowired
    UserService userService;

    @BeforeEach
    void stubEmails() {
        WireMockStubs.stubResendEmail();
    }

    @Test
    void register_createsUserWithHashedPassword() {
        var user = userService.register("reg-test@test.com", "password123");
        assertThat(user.getId()).isNotNull();
        assertThat(user.getPasswordHash()).doesNotContain("password123");
        assertThat(user.getRole()).isEqualTo("USER");
    }

    @Test
    void register_duplicateEmail_throws() {
        userService.register("dup-email@test.com", "password123");
        assertThatThrownBy(() -> userService.register("dup-email@test.com", "password456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already registered");
    }
}
