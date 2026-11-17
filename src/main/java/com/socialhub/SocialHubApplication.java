package com.socialhub;

import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling

public class SocialHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(SocialHubApplication.class, args);
    }
    @Bean
    ApplicationRunner checkExcelMockWorker(
            ApplicationContext context,
            RabbitListenerEndpointRegistry registry) {

        return args -> {
            System.out.println("========== EXCEL MOCK DEBUG ==========");

            System.out.println(
                    "Worker Bean exists: " +
                            context.containsBean("excelMockQueueWorker")
            );

            registry.getListenerContainers().forEach(container -> {
                System.out.println(
                        "Listener: " + container.getClass().getSimpleName()
                                + " | Running: " + container.isRunning()
                );
            });

            System.out.println("======================================");
        };
    }
}