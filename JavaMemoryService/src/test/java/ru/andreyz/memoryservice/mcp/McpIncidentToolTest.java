package ru.andreyz.memoryservice.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.andreyz.memoryservice.domain.Incident;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class McpIncidentToolTest {

    @Autowired
    private IncidentTools incidentTools;

    @Test
    void createIncident_savedWithCorrectStatus() {
        Incident incident = incidentTools.createIncident(
                "DB connection pool exhausted", "P1", "All connections busy");

        assertThat(incident.id()).isNotNull();
        assertThat(incident.title()).isEqualTo("DB connection pool exhausted");
        assertThat(incident.severity()).isEqualTo("P1");
        assertThat(incident.status()).isEqualTo("OPEN");
    }

    @Test
    void resolveIncident_changesStatus() {
        Incident incident = incidentTools.createIncident("Slow queries", "P2", null);

        Incident resolved = incidentTools.resolveIncident(
                incident.id(), "Missing index on orders.created_at", "Add index, monitor query time");

        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.rootCause()).contains("Missing index");
    }
}
