package com.jeelpay.cinema;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;

public final class TestHttpHelper {

    private TestHttpHelper() {}

    public static String loginAndGetSessionCookie(
            TestRestTemplate restTemplate,
            String baseUrl,
            String email,
            String password) {

        ResponseEntity<String> loginPage =
                restTemplate.getForEntity(baseUrl + "/login", String.class);

        String csrf = extractCsrfToken(loginPage.getBody());
        // Spring Session JDBC uses "SESSION", not "JSESSIONID".
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

    public static String extractCsrfToken(String html) {
        if (html == null || html.isBlank()) return "";

        var doc = Jsoup.parse(html);

        Element input = doc.selectFirst("input[name=_csrf]");
        if (input != null) return input.val();

        Element meta = doc.selectFirst("meta[name=_csrf]");
        if (meta != null) return meta.attr("content");

        return "";
    }

    public static String extractCookie(HttpHeaders headers, String cookieName) {
        List<String> setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) return null;

        String prefix = cookieName + "=";
        for (String cookie : setCookies) {
            if (cookie.startsWith(prefix)) {
                return cookie.split(";")[0].substring(prefix.length());
            }
        }
        return null;
    }
}
