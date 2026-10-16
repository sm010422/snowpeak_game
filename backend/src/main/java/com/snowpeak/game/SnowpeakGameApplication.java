package com.snowpeak.game;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class SnowpeakGameApplication {

    public static void main(String[] args) {
        SpringApplication.run(SnowpeakGameApplication.class, args);
    }

}
