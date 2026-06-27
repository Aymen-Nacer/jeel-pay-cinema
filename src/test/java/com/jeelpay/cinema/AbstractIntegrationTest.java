package com.jeelpay.cinema;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

import java.io.IOException;
import java.net.HttpURLConnection;

/**
 * Shared integration-test base. Containers start once in a static block so ports stay
 * stable across cached Spring contexts (avoids @Container stop/restart per class).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
public abstract class AbstractIntegrationTest {

    static final MySQLContainer<?> mysql;
    static final GenericContainer<?> wireMockContainer;

    static {
        mysql = new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("cinema_test")
                .withUsername("test")
                .withPassword("test")
                .withUrlParam("allowPublicKeyRetrieval", "true")
                .withUrlParam("useSSL", "false")
                .withUrlParam("serverTimezone", "UTC");
        mysql.start();

        wireMockContainer = new GenericContainer<>("wiremock/wiremock:3.13.0")
                .withExposedPorts(8080);
        wireMockContainer.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.baseline-on-migrate", () -> "false");

        registry.add("app.moyasar.api-url", () -> wireMockBaseUrl() + "/moyasar");
        registry.add("app.resend.api-url",  () -> wireMockBaseUrl() + "/resend");
        registry.add("app.base-url",        () -> "http://localhost:8080");
    }

    @BeforeAll
    static void configureWireMockClient() {
        WireMock.configureFor(wireMockContainer.getHost(), wireMockContainer.getMappedPort(8080));
    }

    @BeforeEach
    void resetWireMock() {
        WireMock.reset();
    }

    @Autowired(required = false)
    TestRestTemplate testRestTemplate;

    /**
     * TestRestTemplate follows redirects by default, which drops the authenticated session
     * cookie set on the POST /login 302 response.
     */
    @BeforeEach
    void disableRedirectFollowing() {
        if (testRestTemplate != null) {
            var factory = new SimpleClientHttpRequestFactory() {
                @Override
                protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                        throws IOException {
                    super.prepareConnection(connection, httpMethod);
                    connection.setInstanceFollowRedirects(false);
                }
            };
            testRestTemplate.getRestTemplate().setRequestFactory(factory);
        }
    }

    @LocalServerPort
    protected int port;

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    static String wireMockBaseUrl() {
        return "http://" + wireMockContainer.getHost() + ":" + wireMockContainer.getMappedPort(8080);
    }
}
