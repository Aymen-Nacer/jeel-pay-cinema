package com.jeelpay.cinema;

import com.jeelpay.cinema.integration.moyasar.MoyasarPaymentResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MoyasarPaymentResponseTest {

    @Test
    void isPaid_trueOnlyForPaidStatus() {
        assertThat(paymentWithStatus("paid").isPaid()).isTrue();
        assertThat(paymentWithStatus("PAID").isPaid()).isTrue();
        assertThat(paymentWithStatus("failed").isPaid()).isFalse();
        assertThat(paymentWithStatus("initiated").isPaid()).isFalse();
        assertThat(paymentWithStatus(null).isPaid()).isFalse();
    }

    @Test
    void transactionUrl_readFromNestedSource() {
        var resp = new MoyasarPaymentResponse();
        resp.setSource(Map.of("transaction_url", "https://moyasar.test/pay/abc"));
        assertThat(resp.getTransactionUrl()).isEqualTo("https://moyasar.test/pay/abc");
    }

    @Test
    void transactionUrl_nullWhenMissing() {
        var resp = new MoyasarPaymentResponse();
        assertThat(resp.getTransactionUrl()).isNull();
        resp.setSource(Map.of());
        assertThat(resp.getTransactionUrl()).isNull();
    }

    private static MoyasarPaymentResponse paymentWithStatus(String status) {
        var resp = new MoyasarPaymentResponse();
        resp.setStatus(status);
        return resp;
    }
}
