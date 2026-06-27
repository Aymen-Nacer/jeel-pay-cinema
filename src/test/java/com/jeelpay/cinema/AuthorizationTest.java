package com.jeelpay.cinema;

import com.jeelpay.cinema.service.BookingService;
import com.jeelpay.cinema.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationTest extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired UserService userService;
    @Autowired BookingService bookingService;
    @Autowired TestDataFactory testDataFactory;

    @BeforeEach
    void stubEmails() {
        WireMockStubs.stubResendEmail();
    }

    @Test
    void unauthenticated_adminPage_redirectsToLogin() {
        var factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                    throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        var noRedirect = new RestTemplate(factory);
        noRedirect.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(HttpStatusCode statusCode) { return false; }
        });
        ResponseEntity<String> resp =
                noRedirect.getForEntity(baseUrl() + "/admin/bookings", String.class);
        assertThat(resp.getStatusCode().value()).isIn(302, 401, 403);
    }

    @Test
    void user_cannotAccessAdminBookings() {
        userService.register("auth-user@test.com", "password123");
        String session = TestHttpHelper.loginAndGetSessionCookie(
                restTemplate, baseUrl(), "auth-user@test.com", "password123");

        HttpHeaders headers = new HttpHeaders();
        if (session != null) headers.add("Cookie", "SESSION=" + session);

        ResponseEntity<String> resp = restTemplate.exchange(
                baseUrl() + "/admin/bookings", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(resp.getStatusCode().value())
                .as("A USER must never get HTTP 200 on /admin/**")
                .isNotEqualTo(200);
    }

    @Test
    void user_cannotViewAnotherUsersBooking() {
        var owner    = userService.register("owner-auth@test.com",    "password123");
        var intruder = userService.register("intruder-auth@test.com", "password123");

        var ctx = testDataFactory.createShowtimeWithSeats(4, 8);
        String paymentId = "pay-auth-" + java.util.UUID.randomUUID();

        var booking = bookingService.createPendingBooking(
                ctx.showtimeId(), List.of(ctx.firstSeat()), owner.getId());
        WireMockStubs.confirmBookingViaPaidPayment(bookingService, booking, paymentId, 4500L);

        String intruderSession = TestHttpHelper.loginAndGetSessionCookie(
                restTemplate, baseUrl(), "intruder-auth@test.com", "password123");

        HttpHeaders headers = new HttpHeaders();
        if (intruderSession != null) headers.add("Cookie", "SESSION=" + intruderSession);

        ResponseEntity<String> resp = restTemplate.exchange(
                baseUrl() + "/bookings/" + booking.getId(), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(resp.getStatusCode().value())
                .as("An intruder must not receive 200 on another user's booking")
                .isNotEqualTo(200);
    }

    @Test
    void admin_canAccessAdminBookings() {
        String adminSession = TestHttpHelper.loginAndGetSessionCookie(
                restTemplate, baseUrl(), "admin@jeelpay.com", "admin");
        assertThat(adminSession).as("Admin login must create a session").isNotNull();

        HttpHeaders headers = new HttpHeaders();
        headers.add("Cookie", "SESSION=" + adminSession);

        ResponseEntity<String> resp = restTemplate.exchange(
                baseUrl() + "/admin/bookings", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(resp.getStatusCode().value()).isNotIn(401, 403);
    }
}
