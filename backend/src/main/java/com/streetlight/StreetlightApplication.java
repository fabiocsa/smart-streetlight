package com.streetlight;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StreetlightApplication {

    public static void main(String[] args) {
        SpringApplication.run(StreetlightApplication.class, args);
    }
}