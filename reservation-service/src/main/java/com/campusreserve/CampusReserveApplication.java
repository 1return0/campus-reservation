package com.campusreserve;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CampusReserveApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampusReserveApplication.class, args);
    }
}
