package com.cvmatcher.cv_matcher_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.cvmatcher.cv_matcher_backend.identity.SecurityProperties;

@SpringBootApplication
@EnableConfigurationProperties(SecurityProperties.class)
public class CvMatcherBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(CvMatcherBackendApplication.class, args);
	}

}
