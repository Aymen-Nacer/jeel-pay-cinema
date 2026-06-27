package com.jeelpay.cinema.integration.resend;

import com.jeelpay.cinema.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class ResendClient {

    private static final Logger log = LoggerFactory.getLogger(ResendClient.class);

    // Email is a non-critical side effect; keep timeouts tight so it never stalls a flow.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final AppProperties props;

    public ResendClient(AppProperties props) {
        this.props = props;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder()
                .baseUrl(props.getResend().apiUrl())
                .requestFactory(factory)
                .defaultHeaders(h -> h.setBearerAuth(props.getResend().apiKey()))
                .build();
    }

    public void sendEmail(String to, String subject, String html) {
        var body = Map.of(
                "from", props.getResend().from(),
                "to", List.of(to),
                "subject", subject,
                "html", html
        );

        try {
            restClient.post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(status -> status.isError(),
                            (req, res) -> { throw new ResendException("Resend API error: " + res.getStatusCode()); })
                    .toBodilessEntity();

            log.debug("Email sent to {} subject={}", to, subject);
        } catch (RestClientException e) {
            throw new ResendException("Resend unreachable: " + e.getMessage(), e);
        }
    }
}
