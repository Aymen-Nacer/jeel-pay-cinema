package com.jeelpay.cinema.integration.moyasar;

import com.jeelpay.cinema.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

@Component
public class MoyasarClient {

    private static final Logger log = LoggerFactory.getLogger(MoyasarClient.class);

    // Sensible timeouts so a slow/hung Moyasar never ties up a request thread indefinitely.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private final RestClient restClient;
    private final AppProperties props;

    public MoyasarClient(AppProperties props) {
        this.props = props;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder()
                .baseUrl(props.getMoyasar().apiUrl())
                .requestFactory(factory)
                .defaultHeaders(h -> h.setBasicAuth(props.getMoyasar().secretKey(), ""))
                .build();
    }

    /**
     * Create a payment and return the hosted payment page URL (transaction_url).
     * Amount is in SAR; Moyasar expects halala (×100, integer).
     */
    public MoyasarPaymentResponse createPayment(String bookingId, BigDecimal amountSar,
                                                String callbackUrl, String description) {
        long halala = amountSar.multiply(BigDecimal.valueOf(100)).longValue();

        var body = Map.of(
                "amount", halala,
                "currency", "SAR",
                "description", description,
                "callback_url", callbackUrl,
                // booking_id is echoed back in the webhook payload so the server-to-server
                // webhook path can map a payment to its booking without the browser redirect.
                "metadata", Map.of("booking_id", bookingId),
                "source", Map.of("type", "creditcard")
        );

        try {
            return restClient.post()
                    .uri("/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(status -> status.isError(),
                            (req, res) -> { throw new MoyasarException(
                                    "Moyasar payment creation failed: " + res.getStatusCode(),
                                    res.getStatusCode().value()); })
                    .body(MoyasarPaymentResponse.class);
        } catch (MoyasarException e) {
            throw e;
        } catch (RestClientException e) {
            throw new MoyasarException("Moyasar unreachable: " + e.getMessage(), e);
        }
    }

    /**
     * Fetch payment details from Moyasar for verification.
     */
    public MoyasarPaymentResponse getPayment(String paymentId) {
        try {
            return restClient.get()
                    .uri("/payments/{id}", paymentId)
                    .retrieve()
                    .onStatus(status -> status.isError(),
                            (req, res) -> { throw new MoyasarException(
                                    "Moyasar payment fetch failed: " + res.getStatusCode(),
                                    res.getStatusCode().value()); })
                    .body(MoyasarPaymentResponse.class);
        } catch (MoyasarException e) {
            throw e;
        } catch (RestClientException e) {
            throw new MoyasarException("Moyasar unreachable: " + e.getMessage(), e);
        }
    }

    /**
     * Refund the full amount of a payment. Returns the updated payment response.
     */
    public MoyasarPaymentResponse refundPayment(String paymentId, BigDecimal amountSar) {
        long halala = amountSar.multiply(BigDecimal.valueOf(100)).longValue();
        var body = Map.of("amount", halala);

        try {
            return restClient.post()
                    .uri("/payments/{id}/refund", paymentId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(status -> status.isError(),
                            (req, res) -> { throw new MoyasarException(
                                    "Moyasar refund failed: " + res.getStatusCode(),
                                    res.getStatusCode().value()); })
                    .body(MoyasarPaymentResponse.class);
        } catch (MoyasarException e) {
            throw e;
        } catch (RestClientException e) {
            throw new MoyasarException("Moyasar unreachable: " + e.getMessage(), e);
        }
    }
}
