package com.jeelpay.cinema;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * Reusable WireMock stub builders for Moyasar and Resend.
 *
 * All methods use the static {@link com.github.tomakehurst.wiremock.client.WireMock}
 * API, which delegates to the client configured in
 * {@link AbstractIntegrationTest#configureWireMockClient()}. No
 * {@code WireMockServer} parameter is needed — the global client is always
 * pointed at the shared WireMock container.
 */
public final class WireMockStubs {

    private WireMockStubs() {}

    // ── Moyasar ──────────────────────────────────────────────────────────────────

    public static void stubMoyasarCreatePayment(String paymentId, String transactionUrl) {
        stubFor(post(urlPathEqualTo("/moyasar/payments"))
                .willReturn(okJson("""
                        {
                          "id": "%s",
                          "status": "initiated",
                          "amount": 4500,
                          "currency": "SAR",
                          "source": { "transaction_url": "%s" }
                        }
                        """.formatted(paymentId, transactionUrl))));
    }

    public static void stubMoyasarGetPaymentPaid(String paymentId, long amountHalala) {
        stubFor(get(urlPathEqualTo("/moyasar/payments/" + paymentId))
                .willReturn(okJson("""
                        {
                          "id": "%s",
                          "status": "paid",
                          "amount": %d,
                          "currency": "SAR",
                          "source": {}
                        }
                        """.formatted(paymentId, amountHalala))));
    }

    public static void stubMoyasarGetPaymentFailed(String paymentId) {
        stubFor(get(urlPathEqualTo("/moyasar/payments/" + paymentId))
                .willReturn(okJson("""
                        {
                          "id": "%s",
                          "status": "failed",
                          "amount": 4500,
                          "currency": "SAR",
                          "source": {}
                        }
                        """.formatted(paymentId))));
    }

    public static void stubMoyasarRefund(String paymentId) {
        stubFor(post(urlPathEqualTo("/moyasar/payments/" + paymentId + "/refund"))
                .willReturn(okJson("""
                        {
                          "id": "%s",
                          "status": "refunded",
                          "amount": 4500,
                          "currency": "SAR",
                          "source": {}
                        }
                        """.formatted(paymentId))));
    }

    // ── Resend ───────────────────────────────────────────────────────────────────

    public static void stubResendEmail() {
        stubFor(post(urlPathEqualTo("/resend/emails"))
                .willReturn(okJson("{\"id\": \"email-stub-id\"}")));
    }
}
