package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

public class V23__task_external_issues extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String product = connection.getMetaData().getDatabaseProductName();
        if (product != null && product.toLowerCase().contains("postgresql")) {
            runPostgres(connection);
            return;
        }
        runFallback(connection);
    }

    private void runPostgres(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS task_external_issues (
                        id BIGSERIAL PRIMARY KEY,
                        task_id BIGINT NOT NULL REFERENCES tasks(id),
                        external_system VARCHAR(32) NOT NULL,
                        external_id VARCHAR(128),
                        external_key VARCHAR(128),
                        external_url TEXT,
                        project_key VARCHAR(64),
                        status VARCHAR(32) NOT NULL,
                        error_message TEXT,
                        created_at TIMESTAMP NOT NULL DEFAULT now(),
                        updated_at TIMESTAMP NOT NULL DEFAULT now(),
                        CONSTRAINT uq_task_external_issue UNIQUE (task_id, external_system)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_task_external_issues_task_id ON task_external_issues(task_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_task_external_issues_external_key ON task_external_issues(external_key)");
        }
    }

    private void runFallback(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS task_external_issues (
                        id BIGSERIAL PRIMARY KEY,
                        task_id BIGINT NOT NULL,
                        external_system VARCHAR(32) NOT NULL,
                        external_id VARCHAR(128),
                        external_key VARCHAR(128),
                        external_url TEXT,
                        project_key VARCHAR(64),
                        status VARCHAR(32) NOT NULL,
                        error_message TEXT,
                        created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                        updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
                        CONSTRAINT fk_task_external_issues_task
                            FOREIGN KEY (task_id) REFERENCES tasks(id),
                        CONSTRAINT uq_task_external_issue UNIQUE (task_id, external_system)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_task_external_issues_task_id ON task_external_issues(task_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_task_external_issues_external_key ON task_external_issues(external_key)");
        }
    }
}
