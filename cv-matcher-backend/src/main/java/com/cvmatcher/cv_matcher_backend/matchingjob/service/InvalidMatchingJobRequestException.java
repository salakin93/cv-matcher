package com.cvmatcher.cv_matcher_backend.matchingjob.service;

public class InvalidMatchingJobRequestException extends RuntimeException {

    public InvalidMatchingJobRequestException(String message) {
        super(message);
    }
}
