package com.college.labbooking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class LabBookingApplication {
    public static void main(String[] args) {
        SpringApplication.run(LabBookingApplication.class, args);
    }
}
