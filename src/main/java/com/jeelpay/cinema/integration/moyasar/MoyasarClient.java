package com.jeelpay.cinema.integration.moyasar;

import com.jeelpay.cinema.config.AppProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class MoyasarClient {

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
     * Creates a card payment directly via the Payments API. With {@code 3ds = true} the
     * response is {@code initiated} and carries a {@code source.transaction_url} that the
     * customer must visit to complete 3-D Secure authentication.
     */
    public MoyasarPaymentResponse createPayment(String bookingId, BigDecimal amountSar,
                                                String callbackUrl, String description,
                                                CardDetails card) {
        long halala = amountSar.multiply(BigDecimal.valueOf(100)).longValue();

        var source = new LinkedHashMap<String, Object>();
        source.put("type", "creditcard");
        source.put("name", card.name());
        source.put("number", card.number());
        source.put("month", card.month());
        source.put("year", card.year());
        source.put("cvc", card.cvc());
        source.put("3ds", true);
        source.put("manual", false);

        var body = Map.of(
                "amount", halala,
                "currency", "SAR",
                "description", description,
                "callback_url", callbackUrl,
                "metadata", Map.of("booking_id", bookingId),
                "source", source
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
