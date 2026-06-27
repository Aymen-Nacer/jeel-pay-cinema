package com.jeelpay.cinema.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String baseUrl;
    private MoyasarProperties moyasar = new MoyasarProperties(null, null, null, null);
    private ResendProperties resend = new ResendProperties(null, null, null);
    private BookingProperties booking = new BookingProperties(15);

    public AppProperties() {}

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public MoyasarProperties getMoyasar() { return moyasar; }
    public void setMoyasar(MoyasarProperties moyasar) { this.moyasar = moyasar; }
    public ResendProperties getResend() { return resend; }
    public void setResend(ResendProperties resend) { this.resend = resend; }
    public BookingProperties getBooking() { return booking; }
    public void setBooking(BookingProperties booking) { this.booking = booking; }

    public record MoyasarProperties(String apiUrl, String publicKey, String secretKey, String webhookSecret) {}
    public record ResendProperties(String apiUrl, String apiKey, String from) {}
    public record BookingProperties(int seatHoldMinutes) {}
}
