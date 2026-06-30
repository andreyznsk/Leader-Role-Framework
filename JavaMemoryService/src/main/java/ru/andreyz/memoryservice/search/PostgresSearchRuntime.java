package ru.andreyz.memoryservice.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

@Component
public class PostgresSearchRuntime {

    private static final Logger log = LoggerFactory.getLogger(PostgresSearchRuntime.class);

    private final DataSource dataSource;
    private volatile Boolean postgres;

    public PostgresSearchRuntime(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public boolean isPostgres() {
        Boolean cached = postgres;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (postgres != null) {
                return postgres;
            }
            try (Connection connection = dataSource.getConnection()) {
                String productName = connection.getMetaData().getDatabaseProductName();
                postgres = productName != null && productName.toLowerCase().contains("postgresql");
            } catch (Exception e) {
                log.error("", e);
                postgres = false;
            }
            return postgres;
        }
    }
}
