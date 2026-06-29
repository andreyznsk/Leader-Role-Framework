package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

public class V14__task_description_storage extends BaseJavaMigration {

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
                    CREATE TABLE IF NOT EXISTS task_descriptions (
                        id BIGSERIAL PRIMARY KEY,
                        task_id BIGINT NOT NULL UNIQUE,
                        content_md CLOB NOT NULL DEFAULT '',
                        content_hash VARCHAR(64),
                        search_vector VARCHAR,
                        created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                        updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
                        CONSTRAINT fk_task_descriptions_task
                            FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_task_descriptions_task_id ON task_descriptions(task_id)");
        }
    }

    private void runPostgresMigration(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS task_descriptions (
                        id BIGSERIAL PRIMARY KEY,
                        task_id BIGINT NOT NULL UNIQUE REFERENCES tasks(id) ON DELETE CASCADE,
                        content_md TEXT NOT NULL DEFAULT '',
                        content_hash VARCHAR(64),
                        search_vector tsvector,
                        created_at TIMESTAMP NOT NULL DEFAULT now(),
                        updated_at TIMESTAMP NOT NULL DEFAULT now()
                    );
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_task_descriptions_search_vector
                    ON task_descriptions USING GIN(search_vector);
                    CREATE INDEX IF NOT EXISTS idx_task_descriptions_task_id
                    ON task_descriptions(task_id);
                    """);
            statement.execute("""
                    CREATE OR REPLACE FUNCTION update_task_description_search_vector()
                    RETURNS trigger AS $$
                    BEGIN
                        NEW.search_vector :=
                            setweight(to_tsvector('russian', coalesce(NEW.content_md, '')), 'A') ||
                            setweight(to_tsvector('english', coalesce(NEW.content_md, '')), 'B');
                        NEW.updated_at := now();
                        RETURN NEW;
                    END;
                    $$ LANGUAGE plpgsql;
                    """);
            statement.execute("""
                    DROP TRIGGER IF EXISTS trg_task_descriptions_search_vector ON task_descriptions;
                    CREATE TRIGGER trg_task_descriptions_search_vector
                    BEFORE INSERT OR UPDATE OF content_md
                    ON task_descriptions
                    FOR EACH ROW
                    EXECUTE FUNCTION update_task_description_search_vector();
                    """);
            statement.execute("""
                    UPDATE task_descriptions
                    SET search_vector =
                        setweight(to_tsvector('russian', coalesce(content_md, '')), 'A') ||
                        setweight(to_tsvector('english', coalesce(content_md, '')), 'B'),
                        updated_at = COALESCE(updated_at, now());
                    """);
        }
    }
}
