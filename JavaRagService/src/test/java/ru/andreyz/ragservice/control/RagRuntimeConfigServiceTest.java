package ru.andreyz.ragservice.control;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

class RagRuntimeConfigServiceTest {

    @Test
    void descriptor_containsUniversalFields() {
        RagRuntimeConfigService service = runtimeConfigService();

        ControlSettingsResponse response = service.descriptor();

        assertThat(response.pluginCode()).isEqualTo("rag");
        assertThat(response.settings()).containsKeys(
                "enabled",
                "schedulerEnabled",
                "scanIntervalSeconds",
                "ragInboxPath",
                "embeddingModel",
                "topK",
                "opensearchUrl",
                "validationEnabled"
        );
    }

    @Test
    void apply_updatesRuntimeAndStatus() {
        RagRuntimeConfigService service = runtimeConfigService();

        ControlSettingsStatusResponse response = service.apply(Map.of(
                "enabled", "false",
                "schedulerEnabled", "false",
                "scanIntervalSeconds", "15",
                "ragInboxPath", "workspace/rag",
                "embeddingModel", "bge-m3",
                "topK", "12",
                "opensearchUrl", "http://opensearch:9200",
                "validationEnabled", "false"
        ));

        assertThat(response.status()).isEqualTo("APPLIED");
        assertThat(response.applied()).containsEntry("enabled", "false");
        assertThat(service.snapshot().enabled()).isFalse();
        assertThat(service.snapshot().schedulerEnabled()).isFalse();
        assertThat(service.snapshot().scanIntervalSeconds()).isEqualTo(15);
        assertThat(service.snapshot().ragInboxPath()).isEqualTo("workspace/rag");
        assertThat(service.snapshot().embeddingModel()).isEqualTo("bge-m3");
        assertThat(service.snapshot().topK()).isEqualTo(12);
        assertThat(service.snapshot().opensearchUrl()).isEqualTo("http://opensearch:9200");
        assertThat(service.snapshot().validationEnabled()).isFalse();
        assertThat(service.status().status()).isEqualTo("DISABLED");
    }

    @Test
    void shouldScan_honorsEnabledSchedulerAndInterval() {
        RagRuntimeConfigService service = runtimeConfigService();

        assertThat(service.shouldScan(java.time.LocalDateTime.of(2026, 6, 23, 12, 0), null)).isTrue();
        assertThat(service.shouldScan(
                java.time.LocalDateTime.of(2026, 6, 23, 12, 0, 30),
                java.time.LocalDateTime.of(2026, 6, 23, 12, 0)
        )).isFalse();
        assertThat(service.shouldScan(
                java.time.LocalDateTime.of(2026, 6, 23, 12, 1),
                java.time.LocalDateTime.of(2026, 6, 23, 12, 0)
        )).isTrue();

        service.apply(Map.of("enabled", "false"));
        assertThat(service.shouldScan(java.time.LocalDateTime.of(2026, 6, 23, 12, 2), null)).isFalse();
    }

    @Test
    void audit_delegatesToStore() {
        RagControlAuditStore store = mock(RagControlAuditStore.class);
        List<ControlAuditEntry> expected = List.of(
                new ControlAuditEntry(java.time.LocalDateTime.of(2026, 6, 23, 12, 0), "APPLIED", List.of("enabled"), "ok")
        );
        org.mockito.Mockito.when(store.findRecent()).thenReturn(expected);
        RagRuntimeConfigService service = new RagRuntimeConfigService(
                true, true, 60000, "rag-inbox", "mxbai-embed-large", 10, "http://localhost:9200", true, store
        );

        assertThat(service.audit()).isEqualTo(expected);
    }

    private RagRuntimeConfigService runtimeConfigService() {
        RagControlAuditStore store = mock(RagControlAuditStore.class);
        doNothing().when(store).save(anyLong(), any(), any(), any(), any(), any());
        return new RagRuntimeConfigService(
                true,
                true,
                60000,
                "rag-inbox",
                "mxbai-embed-large",
                10,
                "http://localhost:9200",
                true,
                store
        );
    }
}
