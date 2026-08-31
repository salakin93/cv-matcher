package com.cvmatcher.cv_matcher_backend.matchingjob.service;

public class MicrosoftConnectionRequiredException extends RuntimeException {
    public MicrosoftConnectionRequiredException() {
        super("An active Microsoft connection is required");
    }
}
