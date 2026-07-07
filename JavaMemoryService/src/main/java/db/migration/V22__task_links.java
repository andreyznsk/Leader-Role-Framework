package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

public class V22__task_links extends BaseJavaMigration {

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
                    CREATE TABLE IF NOT EXISTS task_links (
                        id BIGSERIAL PRIMARY KEY,
                        from_task_id BIGINT NOT NULL,
                        to_task_id   BIGINT NOT NULL,
                        link_type VARCHAR(16) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                        CONSTRAINT fk_task_links_from
                            FOREIGN KEY (from_task_id) REFERENCES tasks(id) ON DELETE CASCADE,
                        CONSTRAINT fk_task_links_to
                            FOREIGN KEY (to_task_id) REFERENCES tasks(id) ON DELETE CASCADE,
                        CONSTRAINT uq_task_links UNIQUE (from_task_id, to_task_id, link_type),
                        CONSTRAINT chk_no_self_link CHECK (from_task_id <> to_task_id)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_task_links_from ON task_links(from_task_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_task_links_to ON task_links(to_task_id)");
        }
    }

    private void runPostgresMigration(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS task_links (
                        id BIGSERIAL PRIMARY KEY,
                        from_task_id BIGINT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
                        to_task_id   BIGINT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
                        link_type VARCHAR(16) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT now(),
                        CONSTRAINT uq_task_links UNIQUE (from_task_id, to_task_id, link_type),
                        CONSTRAINT chk_no_self_link CHECK (from_task_id <> to_task_id)
                    );
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_task_links_from ON task_links(from_task_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_task_links_to ON task_links(to_task_id)");
        }
    }
}
