package com.cvmatcher.cv_matcher_backend.vacancy.application;

import org.springframework.http.HttpStatus;

public class VacancyException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public VacancyException(HttpStatus status, String code) {
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
