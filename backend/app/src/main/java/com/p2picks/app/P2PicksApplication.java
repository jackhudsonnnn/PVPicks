package com.p2picks.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic(additionalPackages = "com.p2picks.modules")
@SpringBootApplication(scanBasePackages = {"com.p2picks.app", "com.p2picks.modules"})
public class P2PicksApplication {

    public static void main(String[] args) {
        SpringApplication.run(P2PicksApplication.class, args);
    }
}
