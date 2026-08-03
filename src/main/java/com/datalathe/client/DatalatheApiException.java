package com.datalathe.client;

import java.io.IOException;

/**
 * Thrown when the engine returns a structured error body
 * ({@code {"error_code": ..., "message": ...}}). Exposes the HTTP status,
 * machine-readable error code, and server message as typed fields so callers
 * can branch on the cause without matching on exception message text.
 */
public class DatalatheApiException extends IOException {
    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String errorCode;
    private final String serverMessage;

    public DatalatheApiException(String message, int statusCode, String errorCode, String serverMessage) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.serverMessage = serverMessage;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getServerMessage() {
        return serverMessage;
    }
}
