package com.v2x.rsu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RsuApplication {

    public static void main(String[] args) {
        SpringApplication.run(RsuApplication.class, args);
    }
}
