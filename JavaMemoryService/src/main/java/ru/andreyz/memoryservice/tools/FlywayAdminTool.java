package ru.andreyz.memoryservice.tools;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;

/**
 * Standalone Flyway admin entry point, run via `mvn exec:java` (see scripts/flyway-repair.sh).
 *
 * Unlike the standalone flyway-maven-plugin/CLI, this runs with the module's full
 * compile classpath (target/classes + dependencies), so it resolves Java-based
 * migrations under db.migration (e.g. V13, V14, V15, V19) the same way the real
 * Spring Boot app does via `classpath:db/migration`. Running repair through a
 * classpath that can't see those classes makes Flyway treat them as "missing" and
 * delete their history rows — this tool exists specifically to avoid that.
 */
public final class FlywayAdminTool {

    private FlywayAdminTool() {
    }

    public static void main(String[] args) {
        String url = require("FLYWAY_URL");
        String user = require("FLYWAY_USER");
        String password = require("FLYWAY_PASSWORD");
        String command = args.length > 0 ? args[0] : "info";

        boolean outOfOrder = Boolean.parseBoolean(System.getenv().getOrDefault("FLYWAY_OUT_OF_ORDER", "false"));

        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .schemas("memory")
                .locations("classpath:db/migration")
                .outOfOrder(outOfOrder)
                .load();

        switch (command) {
            case "info" -> { }
            case "repair" -> flyway.repair();
            case "migrate" -> flyway.migrate();
            default -> throw new IllegalArgumentException("Unknown command: " + command + " (expected info|repair|migrate)");
        }
        printInfo(flyway);
    }

    private static void printInfo(Flyway flyway) {
        for (MigrationInfo info : flyway.info().all()) {
            System.out.printf(
                    "%-10s %-50s %-10s %-10s%n",
                    info.getVersion(),
                    info.getDescription(),
                    info.getType(),
                    info.getState());
        }
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }
}
