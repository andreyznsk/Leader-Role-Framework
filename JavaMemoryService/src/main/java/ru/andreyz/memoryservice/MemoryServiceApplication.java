package ru.andreyz.memoryservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootApplication
@EnableScheduling
public class MemoryServiceApplication implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MemoryServiceApplication.class);

    @Autowired
    private Environment env;


    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;


    public static void main(String[] args) {
        SpringApplication.run(MemoryServiceApplication.class, args);
    }

    @Override
    public void run(ApplicationArguments args) {
        String port = env.getProperty("server.port", "8082");
        String db   = env.getProperty("spring.datasource.url", "n/a");

        log.info("""

                ======STARTED: JavaMemoryService======
                  App:       http://localhost:{}
                  Health:    http://localhost:{}/actuator/health
                  UI:        http://localhost:{}/ui/today
                  Database:  {}
                =======================================""",
                port, port, port, db);

        log.debug("=== Registered mappings ===");
        requestMappingHandlerMapping.getHandlerMethods().forEach((info, method) ->
                log.debug("{} -> {}", info, method.getShortLogMessage())
        );
        log.debug("=== End of mappings ===");
    }
}
