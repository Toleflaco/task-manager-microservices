package com.mtole.task;

import com.mtole.task.config.TestcontainersConfig;
import org.springframework.boot.SpringApplication;

public class TestTaskServiceApplication {

    public static void main(String[] args) {
        SpringApplication.from(TaskServiceApplication::main)
                .with(TestcontainersConfig.class)
                .run(args);
    }
}
