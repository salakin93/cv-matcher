package com.cvmatcher.cv_matcher_backend.document;

public interface AntivirusPort {
    Result scan(byte[] content);
    enum Result { CLEAN, DETECTED, UNAVAILABLE }
}
