package com.jeelpay.cinema;

import com.jeelpay.cinema.domain.Booking;
import com.jeelpay.cinema.integration.moyasar.CardDetails;
import com.jeelpay.cinema.service.BookingService;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

public final class WireMockStubs {

    public static final CardDetails TEST_CARD =
            new CardDetails("John Doe", "4111111111111111", 12, 2026, "911");

    private WireMockStubs() {}

    public static void stubMoyasarCreatePayment(String paymentId, String transactionUrl) {
        stubFor(post(urlPathEqualTo("/moyasar/payments"))
                .willReturn(okJson("""
                        {
                          "id": "%s",
                          "status": "initiated",
                          "amount": 4500,
                          "currency": "SAR",
                          "source": { "type": "creditcard", "transaction_url": "%s" }
                        }
                        """.formatted(paymentId, transactionUrl))));
    }

    public static void confirmBookingViaPaidPayment(BookingService bookingService, Booking booking,
                                                    String paymentId, long amountHalala) {
        stubMoyasarCreatePayment(paymentId, "https://moyasar.test/pay/" + paymentId);
        bookingService.initiatePayment(booking, TEST_CARD);
        stubMoyasarGetPaymentPaid(paymentId, amountHalala);
        bookingService.confirmPayment(booking.getId());
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

    public static void stubResendEmail() {
        stubFor(post(urlPathEqualTo("/resend/emails"))
                .willReturn(okJson("{\"id\": \"email-stub-id\"}")));
    }
}
