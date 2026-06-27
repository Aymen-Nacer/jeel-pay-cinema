package com.jeelpay.cinema;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;

/**
 * HTTP utilities shared by integration tests that need to drive the real HTTP stack.
 *
 * <h3>Why Jsoup instead of {@code String.indexOf}?</h3>
 * The previous approach searched the raw HTML string for {@code name="_csrf"} and
 * then extracted the {@code value} attribute by counting characters. Any change in
 * whitespace, attribute order, or Thymeleaf output format would silently break that
 * extraction. Jsoup parses the full DOM and uses CSS selectors, so it is robust
 * against cosmetic template changes.
 */
public final class TestHttpHelper {

    private TestHttpHelper() {}

    // ── Session cookie ───────────────────────────────────────────────────────────

    /**
     * Performs a form-based login and returns the JSESSIONID cookie value.
     *
     * Mirrors the real browser flow:
     * <ol>
     *   <li>GET /login — obtain the CSRF token and pre-authentication session.</li>
     *   <li>POST /login with credentials + token — Spring Security issues a new
     *       session on successful login (session-fixation protection).</li>
     * </ol>
     *
     * @return the new JSESSIONID value, or {@code null} if login failed
     */
    public static String loginAndGetSessionCookie(
            TestRestTemplate restTemplate,
            String baseUrl,
            String email,
            String password) {

        ResponseEntity<String> loginPage =
                restTemplate.getForEntity(baseUrl + "/login", String.class);

        String csrf = extractCsrfToken(loginPage.getBody());
        // Spring Session JDBC uses "SESSION" as its default cookie name, not "JSESSIONID".
        String preLoginSession = extractCookie(loginPage.getHeaders(), "SESSION");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (preLoginSession != null) {
            headers.add("Cookie", "SESSION=" + preLoginSession);
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", email);
        form.add("password", password);
        form.add("_csrf", csrf);

        ResponseEntity<String> loginResponse = restTemplate.exchange(
                baseUrl + "/login", HttpMethod.POST,
                new HttpEntity<>(form, headers), String.class);

        return extractCookie(loginResponse.getHeaders(), "SESSION");
    }

    // ── CSRF token extraction ────────────────────────────────────────────────────

    /**
     * Extracts the CSRF token value from an HTML page rendered by Thymeleaf.
     *
     * Thymeleaf + Spring Security renders the token as:
     * <pre>{@code <input type="hidden" name="_csrf" value="TOKEN"/>}</pre>
     * or as an HTTP meta tag:
     * <pre>{@code <meta name="_csrf" content="TOKEN"/>}</pre>
     *
     * Jsoup's CSS selector handles both forms without fragile string parsing.
     */
    public static String extractCsrfToken(String html) {
        if (html == null || html.isBlank()) return "";

        var doc = Jsoup.parse(html);

        // Hidden input field (login and form pages)
        Element input = doc.selectFirst("input[name=_csrf]");
        if (input != null) return input.val();

        // Meta tag (some page layouts)
        Element meta = doc.selectFirst("meta[name=_csrf]");
        if (meta != null) return meta.attr("content");

        return "";
    }

    // ── Cookie extraction ────────────────────────────────────────────────────────

    /**
     * Extracts the value of a named cookie from {@code Set-Cookie} response headers.
     *
     * @return the cookie value, or {@code null} if the cookie is not present
     */
    public static String extractCookie(HttpHeaders headers, String cookieName) {
        List<String> setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) return null;

        String prefix = cookieName + "=";
        for (String cookie : setCookies) {
            if (cookie.startsWith(prefix)) {
                // Cookie header format: "NAME=VALUE; Path=/; HttpOnly; ..."
                return cookie.split(";")[0].substring(prefix.length());
            }
        }
        return null;
    }
}
