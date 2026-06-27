package com.jeelpay.cinema.integration.resend;

public class ResendException extends RuntimeException {

    public ResendException(String message) {
        super(message);
    }

    public ResendException(String message, Throwable cause) {
        super(message, cause);
    }
}
