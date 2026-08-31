package com.cvmatcher.cv_matcher_backend.ingestion.service;

public interface TextExtractor { String extract(byte[] content, String contentType); }
