package com.p2picks.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic
@SpringBootApplication
public class P2PicksApplication {

    public static void main(String[] args) {
        SpringApplication.run(P2PicksApplication.class, args);
    }
}
