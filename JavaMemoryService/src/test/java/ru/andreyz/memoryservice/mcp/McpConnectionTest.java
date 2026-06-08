package ru.andreyz.memoryservice.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class McpConnectionTest {

    @Autowired
    private ContextTools contextTools;

    @Autowired
    private TaskTools taskTools;

    @Autowired
    private IncidentTools incidentTools;

    @Autowired
    private RiskTools riskTools;

    @Autowired
    private PeopleTools peopleTools;

    @Test
    void allToolBeansArePresent() {
        assertThat(contextTools).isNotNull();
        assertThat(taskTools).isNotNull();
        assertThat(incidentTools).isNotNull();
        assertThat(riskTools).isNotNull();
        assertThat(peopleTools).isNotNull();
    }
}
