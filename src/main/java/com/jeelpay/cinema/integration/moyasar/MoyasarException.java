package com.jeelpay.cinema.integration.moyasar;

public class MoyasarException extends RuntimeException {

    private final int statusCode;

    public MoyasarException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public MoyasarException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    public int getStatusCode() { return statusCode; }
}
