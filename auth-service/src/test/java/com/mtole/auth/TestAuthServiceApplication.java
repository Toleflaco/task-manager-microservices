package com.mtole.auth;

import org.springframework.boot.SpringApplication;
import com.mtole.auth.config.TestcontainersConfig;

public class TestAuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.from(AuthServiceApplication::main)
                .with(TestcontainersConfig.class)
                .run(args);
    }
}
