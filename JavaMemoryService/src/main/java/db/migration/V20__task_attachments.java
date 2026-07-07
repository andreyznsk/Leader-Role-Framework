package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

public class V20__task_attachments extends BaseJavaMigration {

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
                    CREATE TABLE IF NOT EXISTS task_attachments (
                        id BIGSERIAL PRIMARY KEY,
                        task_id BIGINT NOT NULL,
                        kind VARCHAR(8) NOT NULL,
                        filename VARCHAR(255),
                        title VARCHAR(255),
                        url CLOB,
                        mime_type VARCHAR(127),
                        file_size BIGINT,
                        storage_ref CLOB,
                        created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                        CONSTRAINT fk_task_attachments_task
                            FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_task_attachments_task_id ON task_attachments(task_id)");
        }
    }

    private void runPostgresMigration(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS task_attachments (
                        id BIGSERIAL PRIMARY KEY,
                        task_id BIGINT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
                        kind VARCHAR(8) NOT NULL,
                        filename VARCHAR(255),
                        title VARCHAR(255),
                        url TEXT,
                        mime_type VARCHAR(127),
                        file_size BIGINT,
                        storage_ref TEXT,
                        created_at TIMESTAMP NOT NULL DEFAULT now()
                    );
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_task_attachments_task_id ON task_attachments(task_id)");
        }
    }
}
