package com.cvmatcher.cv_matcher_backend.administration.application;

import org.springframework.http.HttpStatus;

public class AdministrationException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public AdministrationException(HttpStatus status, String code) {
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
