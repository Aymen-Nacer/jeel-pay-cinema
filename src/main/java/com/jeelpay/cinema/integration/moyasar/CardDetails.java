package com.jeelpay.cinema.integration.moyasar;

/**
 * Card data collected from the checkout form and forwarded to the Moyasar
 * Payments API as a {@code creditcard} source. Never persisted.
 */
public record CardDetails(String name, String number, int month, int year, String cvc) {}
