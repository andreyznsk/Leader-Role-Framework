package ru.andreyz.memoryservice.search;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class PostgresSearchRuntime {


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
