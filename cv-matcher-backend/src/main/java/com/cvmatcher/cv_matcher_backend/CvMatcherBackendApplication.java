package com.cvmatcher.cv_matcher_backend;

import com.cvmatcher.cv_matcher_backend.identity.CorsProperties;
import com.cvmatcher.cv_matcher_backend.identity.SecurityProperties;
import com.cvmatcher.cv_matcher_backend.outlook.OutlookProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({SecurityProperties.class, CorsProperties.class, OutlookProperties.class})
public class CvMatcherBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(CvMatcherBackendApplication.class, args);
    }

}
