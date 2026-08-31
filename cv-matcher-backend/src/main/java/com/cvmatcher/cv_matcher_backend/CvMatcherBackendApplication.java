package com.cvmatcher.cv_matcher_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class CvMatcherBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(CvMatcherBackendApplication.class, args);
	}

}
