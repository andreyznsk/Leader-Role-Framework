package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

public class V19__intake_gateway extends BaseJavaMigration {

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
                    CREATE TABLE IF NOT EXISTS intake_items (
                        id UUID PRIMARY KEY,
                        source_type VARCHAR(32) NOT NULL,
                        source_id CLOB,
                        source_payload_json CLOB,
                        source_text CLOB,
                        agent_provider VARCHAR(32),
                        agent_prompt CLOB,
                        agent_result_json CLOB,
                        agent_result_text CLOB,
                        suggested_route VARCHAR(32),
                        suggested_payload_json CLOB,
                        final_route VARCHAR(32),
                        final_payload_json CLOB,
                        status VARCHAR(32) NOT NULL,
                        confidence DECIMAL(5,4),
                        created_by VARCHAR(128),
                        reviewed_by VARCHAR(128),
                        created_at TIMESTAMP NOT NULL,
                        reviewed_at TIMESTAMP,
                        applied_at TIMESTAMP,
                        rejected_at TIMESTAMP,
                        reject_reason CLOB
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_intake_items_status_created_at ON intake_items(status, created_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_intake_items_source_type_created_at ON intake_items(source_type, created_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_intake_items_suggested_route_status ON intake_items(suggested_route, status)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_intake_items_source_id ON intake_items(source_id)");
        }
    }

    private void runPostgresMigration(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS intake_items (
                        id UUID PRIMARY KEY,
                        source_type VARCHAR(32) NOT NULL,
                        source_id TEXT,
                        source_payload_json JSONB,
                        source_text TEXT,
                        agent_provider VARCHAR(32),
                        agent_prompt TEXT,
                        agent_result_json JSONB,
                        agent_result_text TEXT,
                        suggested_route VARCHAR(32),
                        suggested_payload_json JSONB,
                        final_route VARCHAR(32),
                        final_payload_json JSONB,
                        status VARCHAR(32) NOT NULL,
                        confidence NUMERIC(5,4),
                        created_by VARCHAR(128),
                        reviewed_by VARCHAR(128),
                        created_at TIMESTAMP NOT NULL,
                        reviewed_at TIMESTAMP,
                        applied_at TIMESTAMP,
                        rejected_at TIMESTAMP,
                        reject_reason TEXT
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_intake_items_status_created_at ON intake_items(status, created_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_intake_items_source_type_created_at ON intake_items(source_type, created_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_intake_items_suggested_route_status ON intake_items(suggested_route, status)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_intake_items_source_id ON intake_items(source_id)");
        }
    }
}
