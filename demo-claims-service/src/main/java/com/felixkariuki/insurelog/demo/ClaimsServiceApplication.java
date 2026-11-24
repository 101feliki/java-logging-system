package com.felixkariuki.insurelog.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Example insurance microservice. Notice there is no InsureLog setup code here:
 * adding the starter dependency and setting {@code insurelog.server.url} is all it takes.
 */
@SpringBootApplication
@EnableScheduling
public class ClaimsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClaimsServiceApplication.class, args);
    }
}
