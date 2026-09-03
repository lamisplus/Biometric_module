package org.lamisplus.modules.biometric.controller;

import org.springframework.http.HttpStatus;

public class BiometricOperationException extends RuntimeException {

    private final HttpStatus status;

    public BiometricOperationException(String message) {
        this(HttpStatus.BAD_REQUEST, message);
    }

    public BiometricOperationException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
