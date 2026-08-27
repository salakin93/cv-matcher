package com.cvmatcher.cv_matcher_backend;

import org.springframework.boot.SpringApplication;

public class TestCvMatcherBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(CvMatcherBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
