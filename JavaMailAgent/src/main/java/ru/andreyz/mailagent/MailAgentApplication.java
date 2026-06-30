package ru.andreyz.mailagent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootApplication
@EnableScheduling
public class MailAgentApplication implements ApplicationRunner {


    @Autowired
    private Environment env;

    public static void main(String[] args) {
        SpringApplication.run(MailAgentApplication.class, args);
    }

    @Override
    public void run(ApplicationArguments args) {
        String port          = env.getProperty("server.port", "8080");
        String db            = env.getProperty("spring.datasource.url", "n/a");
        String maildevApi    = env.getProperty("maildev.api-url", "n/a");
        String memoryService = env.getProperty("memory.service.url", "n/a");

        log.info("""

                ======STARTED: JavaMailAgent======
                  App:            http://localhost:{}
                  Health:         http://localhost:{}/actuator/health
                  UI:             http://localhost:{}/ui/status
                  Database:       {}
                  Maildev API:    {}
                  MemoryService:  {}
                ==================================""",
                port, port, port, db, maildevApi, memoryService);
    }
}
