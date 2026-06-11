package ru.andreyz.ragservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RagServiceApplication implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RagServiceApplication.class);

    @Autowired
    private Environment env;

    public static void main(String[] args) {
        SpringApplication.run(RagServiceApplication.class, args);
    }

    @Override
    public void run(ApplicationArguments args) {
        String port       = env.getProperty("server.port", "8081");
        String db         = env.getProperty("spring.datasource.url", "n/a");
        String opensearch = env.getProperty("opensearch.url", "n/a");
        String ollama     = env.getProperty("ollama.url", "n/a");

        log.info("""

                ======STARTED: JavaRagService======
                  App:        http://localhost:{}
                  Health:     http://localhost:{}/actuator/health
                  Database:   {}
                  OpenSearch: {}
                  Ollama:     {}
                ===================================""",
                port, port, db, opensearch, ollama);
    }
}
