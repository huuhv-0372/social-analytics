package com.huuhv.socialanalytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SocialAnalyticsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SocialAnalyticsApplication.class, args);
    }
}
