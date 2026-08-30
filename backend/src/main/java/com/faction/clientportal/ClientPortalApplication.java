package com.faction.clientportal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ClientPortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClientPortalApplication.class, args);
    }
}
