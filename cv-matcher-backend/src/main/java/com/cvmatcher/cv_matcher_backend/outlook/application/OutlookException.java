package com.cvmatcher.cv_matcher_backend.outlook.application;

import org.springframework.http.HttpStatus;

public class OutlookException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public OutlookException(HttpStatus s, String c) {
        status = s;
        code = c;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
