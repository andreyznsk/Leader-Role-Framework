package ru.andreyz.mailagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MailAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(MailAgentApplication.class, args);
    }
}
