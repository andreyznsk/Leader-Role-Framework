package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

public class V15__task_timeline_audit extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String product = connection.getMetaData().getDatabaseProductName();
        if (product != null && product.toLowerCase().contains("postgresql")) {
            runPostgresMigration(connection);
            return;
        }
        runFallbackMigration(connection);
    }

    private void runFallbackMigration(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS task_events (
                        id BIGSERIAL PRIMARY KEY,
                        task_id BIGINT NOT NULL,
                        event_type VARCHAR(64) NOT NULL,
                        actor_type VARCHAR(32) NOT NULL,
                        actor_name VARCHAR(255),
                        old_value_json CLOB,
                        new_value_json CLOB,
                        source_type VARCHAR(32),
                        source_id VARCHAR(255),
                        summary CLOB,
                        created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                        CONSTRAINT fk_task_events_task
                            FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_task_events_task_created_at ON task_events(task_id, created_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_task_events_source ON task_events(source_type, source_id)");
        }
    }

    private void runPostgresMigration(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS task_events (
                        id BIGSERIAL PRIMARY KEY,
                        task_id BIGINT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
                        event_type VARCHAR(64) NOT NULL,
                        actor_type VARCHAR(32) NOT NULL,
                        actor_name VARCHAR(255),
                        old_value_json TEXT,
                        new_value_json TEXT,
                        source_type VARCHAR(32),
                        source_id VARCHAR(255),
                        summary TEXT,
                        created_at TIMESTAMP NOT NULL DEFAULT now()
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_task_events_task_created_at ON task_events(task_id, created_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_task_events_source ON task_events(source_type, source_id)");
        }
    }
}
