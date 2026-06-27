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
 * Shared base for all integration tests.
 *
 * Infrastructure containers (MySQL, WireMock) are started once in a static
 * initializer block when this class is first loaded by the JVM. They stay alive
 * for the entire test run and are cleaned up by the Testcontainers Ryuk reaper
 * on JVM exit.
 *
 * Using a static initializer (rather than {@code @Container} annotations) is
 * the correct "singleton container" pattern for Spring Boot test-context caching:
 * {@code @Container} causes Testcontainers to stop containers after each test
 * class finishes, which changes the mapped ports and invalidates the cached
 * Spring {@code ApplicationContext}, leading to "Connection refused" errors in
 * subsequent test classes.
 *
 * WireMock is managed as a plain {@link GenericContainer} running the official
 * WireMock Docker image. The WireMock Java client is pointed at the container via
 * {@link WireMock#configureFor} in {@code @BeforeAll}; stub state is reset before
 * each test, so every test starts with a clean WireMock slate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
public abstract class AbstractIntegrationTest {

    // ── Shared infrastructure containers ────────────────────────────────────────

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

    // ── Spring property overrides ────────────────────────────────────────────────

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.baseline-on-migrate", () -> "false");

        // Both Moyasar and Resend calls are routed to the same WireMock container.
        // The path prefix (/moyasar, /resend) distinguishes them — WireMock stubs
        // must match these prefixes (e.g. urlPathEqualTo("/moyasar/payments")).
        registry.add("app.moyasar.api-url", () -> wireMockBaseUrl() + "/moyasar");
        registry.add("app.resend.api-url",  () -> wireMockBaseUrl() + "/resend");
        registry.add("app.base-url",        () -> "http://localhost:8080");
    }

    // ── WireMock client configuration ───────────────────────────────────────────

    /**
     * Point the WireMock Java client at the running container once per test class.
     * Because the container is static (singleton), the host:port never changes and
     * this configuration remains valid for every subsequent test in the run.
     */
    @BeforeAll
    static void configureWireMockClient() {
        WireMock.configureFor(wireMockContainer.getHost(), wireMockContainer.getMappedPort(8080));
    }

    /**
     * Reset all WireMock stubs and request journal before each test so that
     * assertions from one test cannot bleed into another. Subclasses may add
     * their own {@code @BeforeEach} stub setup after this reset.
     */
    @BeforeEach
    void resetWireMock() {
        WireMock.reset();
    }

    // ── HTTP client redirect handling ─────────────────────────────────────────────

    @Autowired(required = false)
    TestRestTemplate testRestTemplate;

    /**
     * Force the shared {@link TestRestTemplate} to NOT follow redirects.
     *
     * The Spring Boot 4 {@code TestRestTemplate} follows 3xx redirects by default.
     * That breaks session-based login assertions over HTTP: the {@code Set-Cookie}
     * carrying the freshly issued (authenticated) session id is set on the {@code 302}
     * response from {@code POST /login}, but when the client transparently follows the
     * redirect, the test only sees the headers of the final {@code 200} page and the
     * authenticated session cookie is lost — every subsequent request then behaves as
     * if unauthenticated. Disabling redirect following lets tests observe the raw
     * {@code 302} (and its cookie), and lets {@code POST}-then-redirect flows such as
     * {@code /book} assert the {@code 302} to the Moyasar payment page directly.
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

    // ── Helpers ──────────────────────────────────────────────────────────────────

    @LocalServerPort
    protected int port;

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    /** Base URL of the WireMock container (e.g. {@code http://localhost:54321}). */
    static String wireMockBaseUrl() {
        return "http://" + wireMockContainer.getHost() + ":" + wireMockContainer.getMappedPort(8080);
    }
}
