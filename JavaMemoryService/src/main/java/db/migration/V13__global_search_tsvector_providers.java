package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

public class V13__global_search_tsvector_providers extends BaseJavaMigration {

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
            statement.execute("ALTER TABLE tasks ADD COLUMN IF NOT EXISTS search_vector VARCHAR");
            statement.execute("ALTER TABLE notes ADD COLUMN IF NOT EXISTS search_vector VARCHAR");
            statement.execute("ALTER TABLE people ADD COLUMN IF NOT EXISTS search_vector VARCHAR");
            statement.execute("ALTER TABLE risks ADD COLUMN IF NOT EXISTS search_vector VARCHAR");
            statement.execute("ALTER TABLE incidents ADD COLUMN IF NOT EXISTS search_vector VARCHAR");
        }
    }

    private void runPostgresMigration(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    ALTER TABLE tasks ADD COLUMN IF NOT EXISTS search_vector tsvector;
                    ALTER TABLE notes ADD COLUMN IF NOT EXISTS search_vector tsvector;
                    ALTER TABLE people ADD COLUMN IF NOT EXISTS search_vector tsvector;
                    ALTER TABLE risks ADD COLUMN IF NOT EXISTS search_vector tsvector;
                    ALTER TABLE incidents ADD COLUMN IF NOT EXISTS search_vector tsvector;
                    """);

            statement.execute("""
                    CREATE OR REPLACE FUNCTION update_task_search_vector()
                    RETURNS trigger AS $$
                    BEGIN
                        NEW.search_vector :=
                            setweight(to_tsvector('russian', coalesce(NEW.title, '')), 'A') ||
                            setweight(to_tsvector('english', coalesce(NEW.title, '')), 'A') ||
                            setweight(to_tsvector('russian', coalesce(NEW.description, '')), 'B') ||
                            setweight(to_tsvector('english', coalesce(NEW.description, '')), 'B') ||
                            setweight(to_tsvector('simple', coalesce(NEW.status, '')), 'C') ||
                            setweight(to_tsvector('simple', coalesce(NEW.priority, '')), 'C') ||
                            setweight(to_tsvector('simple', coalesce(NEW.source, '')), 'D');
                        RETURN NEW;
                    END;
                    $$ LANGUAGE plpgsql;
                    """);
            statement.execute("""
                    CREATE OR REPLACE FUNCTION update_note_search_vector()
                    RETURNS trigger AS $$
                    BEGIN
                        NEW.search_vector :=
                            setweight(to_tsvector('russian', coalesce(NEW.title, '')), 'A') ||
                            setweight(to_tsvector('english', coalesce(NEW.title, '')), 'A') ||
                            setweight(to_tsvector('russian', coalesce(NEW.text, '')), 'B') ||
                            setweight(to_tsvector('english', coalesce(NEW.text, '')), 'B') ||
                            setweight(to_tsvector('simple', coalesce(NEW.tags, '')), 'C') ||
                            setweight(to_tsvector('simple', coalesce(NEW.source, '')), 'D');
                        RETURN NEW;
                    END;
                    $$ LANGUAGE plpgsql;
                    """);
            statement.execute("""
                    CREATE OR REPLACE FUNCTION update_person_search_vector()
                    RETURNS trigger AS $$
                    BEGIN
                        NEW.search_vector :=
                            setweight(to_tsvector('russian', coalesce(NEW.full_name, '')), 'A') ||
                            setweight(to_tsvector('english', coalesce(NEW.full_name, '')), 'A') ||
                            setweight(to_tsvector('simple', coalesce(NEW.login, '')), 'A') ||
                            setweight(to_tsvector('simple', coalesce(NEW.email, '')), 'A') ||
                            setweight(to_tsvector('simple', coalesce(NEW.domain, '')), 'B') ||
                            setweight(to_tsvector('russian', coalesce(NEW.current_task, '')), 'B') ||
                            setweight(to_tsvector('english', coalesce(NEW.current_task, '')), 'B') ||
                            setweight(to_tsvector('russian', coalesce(NEW.notes, '')), 'C') ||
                            setweight(to_tsvector('english', coalesce(NEW.notes, '')), 'C');
                        RETURN NEW;
                    END;
                    $$ LANGUAGE plpgsql;
                    """);
            statement.execute("""
                    CREATE OR REPLACE FUNCTION update_risk_search_vector()
                    RETURNS trigger AS $$
                    BEGIN
                        NEW.search_vector :=
                            setweight(to_tsvector('russian', coalesce(NEW.title, '')), 'A') ||
                            setweight(to_tsvector('english', coalesce(NEW.title, '')), 'A') ||
                            setweight(to_tsvector('russian', coalesce(NEW.description, '')), 'B') ||
                            setweight(to_tsvector('english', coalesce(NEW.description, '')), 'B') ||
                            setweight(to_tsvector('simple', coalesce(NEW.impact, '')), 'C') ||
                            setweight(to_tsvector('simple', coalesce(NEW.status, '')), 'C') ||
                            setweight(to_tsvector('russian', coalesce(NEW.mitigation, '')), 'B') ||
                            setweight(to_tsvector('english', coalesce(NEW.mitigation, '')), 'B');
                        RETURN NEW;
                    END;
                    $$ LANGUAGE plpgsql;
                    """);
            statement.execute("""
                    CREATE OR REPLACE FUNCTION update_incident_search_vector()
                    RETURNS trigger AS $$
                    BEGIN
                        NEW.search_vector :=
                            setweight(to_tsvector('russian', coalesce(NEW.title, '')), 'A') ||
                            setweight(to_tsvector('english', coalesce(NEW.title, '')), 'A') ||
                            setweight(to_tsvector('russian', coalesce(NEW.description, '')), 'B') ||
                            setweight(to_tsvector('english', coalesce(NEW.description, '')), 'B') ||
                            setweight(to_tsvector('russian', coalesce(NEW.root_cause, '')), 'B') ||
                            setweight(to_tsvector('english', coalesce(NEW.root_cause, '')), 'B') ||
                            setweight(to_tsvector('russian', coalesce(NEW.action_items, '')), 'C') ||
                            setweight(to_tsvector('english', coalesce(NEW.action_items, '')), 'C') ||
                            setweight(to_tsvector('simple', coalesce(NEW.status, '')), 'C') ||
                            setweight(to_tsvector('simple', coalesce(NEW.severity, '')), 'C');
                        RETURN NEW;
                    END;
                    $$ LANGUAGE plpgsql;
                    """);

            statement.execute("""
                    DROP TRIGGER IF EXISTS trg_tasks_search_vector ON tasks;
                    CREATE TRIGGER trg_tasks_search_vector
                    BEFORE INSERT OR UPDATE OF title, description, status, priority, source
                    ON tasks
                    FOR EACH ROW
                    EXECUTE FUNCTION update_task_search_vector();

                    DROP TRIGGER IF EXISTS trg_notes_search_vector ON notes;
                    CREATE TRIGGER trg_notes_search_vector
                    BEFORE INSERT OR UPDATE OF title, text, tags, source
                    ON notes
                    FOR EACH ROW
                    EXECUTE FUNCTION update_note_search_vector();

                    DROP TRIGGER IF EXISTS trg_people_search_vector ON people;
                    CREATE TRIGGER trg_people_search_vector
                    BEFORE INSERT OR UPDATE OF full_name, login, email, domain, current_task, notes
                    ON people
                    FOR EACH ROW
                    EXECUTE FUNCTION update_person_search_vector();

                    DROP TRIGGER IF EXISTS trg_risks_search_vector ON risks;
                    CREATE TRIGGER trg_risks_search_vector
                    BEFORE INSERT OR UPDATE OF title, description, impact, status, mitigation
                    ON risks
                    FOR EACH ROW
                    EXECUTE FUNCTION update_risk_search_vector();

                    DROP TRIGGER IF EXISTS trg_incidents_search_vector ON incidents;
                    CREATE TRIGGER trg_incidents_search_vector
                    BEFORE INSERT OR UPDATE OF title, description, root_cause, action_items, status, severity
                    ON incidents
                    FOR EACH ROW
                    EXECUTE FUNCTION update_incident_search_vector();
                    """);

            statement.execute("""
                    UPDATE tasks
                    SET search_vector =
                        setweight(to_tsvector('russian', coalesce(title, '')), 'A') ||
                        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
                        setweight(to_tsvector('russian', coalesce(description, '')), 'B') ||
                        setweight(to_tsvector('english', coalesce(description, '')), 'B') ||
                        setweight(to_tsvector('simple', coalesce(status, '')), 'C') ||
                        setweight(to_tsvector('simple', coalesce(priority, '')), 'C') ||
                        setweight(to_tsvector('simple', coalesce(source, '')), 'D');

                    UPDATE notes
                    SET search_vector =
                        setweight(to_tsvector('russian', coalesce(title, '')), 'A') ||
                        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
                        setweight(to_tsvector('russian', coalesce(text, '')), 'B') ||
                        setweight(to_tsvector('english', coalesce(text, '')), 'B') ||
                        setweight(to_tsvector('simple', coalesce(tags, '')), 'C') ||
                        setweight(to_tsvector('simple', coalesce(source, '')), 'D');

                    UPDATE people
                    SET search_vector =
                        setweight(to_tsvector('russian', coalesce(full_name, '')), 'A') ||
                        setweight(to_tsvector('english', coalesce(full_name, '')), 'A') ||
                        setweight(to_tsvector('simple', coalesce(login, '')), 'A') ||
                        setweight(to_tsvector('simple', coalesce(email, '')), 'A') ||
                        setweight(to_tsvector('simple', coalesce(domain, '')), 'B') ||
                        setweight(to_tsvector('russian', coalesce(current_task, '')), 'B') ||
                        setweight(to_tsvector('english', coalesce(current_task, '')), 'B') ||
                        setweight(to_tsvector('russian', coalesce(notes, '')), 'C') ||
                        setweight(to_tsvector('english', coalesce(notes, '')), 'C');

                    UPDATE risks
                    SET search_vector =
                        setweight(to_tsvector('russian', coalesce(title, '')), 'A') ||
                        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
                        setweight(to_tsvector('russian', coalesce(description, '')), 'B') ||
                        setweight(to_tsvector('english', coalesce(description, '')), 'B') ||
                        setweight(to_tsvector('simple', coalesce(impact, '')), 'C') ||
                        setweight(to_tsvector('simple', coalesce(status, '')), 'C') ||
                        setweight(to_tsvector('russian', coalesce(mitigation, '')), 'B') ||
                        setweight(to_tsvector('english', coalesce(mitigation, '')), 'B');

                    UPDATE incidents
                    SET search_vector =
                        setweight(to_tsvector('russian', coalesce(title, '')), 'A') ||
                        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
                        setweight(to_tsvector('russian', coalesce(description, '')), 'B') ||
                        setweight(to_tsvector('english', coalesce(description, '')), 'B') ||
                        setweight(to_tsvector('russian', coalesce(root_cause, '')), 'B') ||
                        setweight(to_tsvector('english', coalesce(root_cause, '')), 'B') ||
                        setweight(to_tsvector('russian', coalesce(action_items, '')), 'C') ||
                        setweight(to_tsvector('english', coalesce(action_items, '')), 'C') ||
                        setweight(to_tsvector('simple', coalesce(status, '')), 'C') ||
                        setweight(to_tsvector('simple', coalesce(severity, '')), 'C');
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_tasks_search_vector ON tasks USING GIN (search_vector);
                    CREATE INDEX IF NOT EXISTS idx_notes_search_vector ON notes USING GIN (search_vector);
                    CREATE INDEX IF NOT EXISTS idx_people_search_vector ON people USING GIN (search_vector);
                    CREATE INDEX IF NOT EXISTS idx_risks_search_vector ON risks USING GIN (search_vector);
                    CREATE INDEX IF NOT EXISTS idx_incidents_search_vector ON incidents USING GIN (search_vector);
                    """);
        }
    }
}
