package ru.andreyz.mailagent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.ControlSettingsResponse;
import ru.andreyz.mailagent.model.ControlSettingsStatusResponse;
import ru.andreyz.mailagent.model.ControlStatusResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MailRuntimeConfigServiceTest {

    private MailRuntimeConfigService service;
    private MailControlAuditStore auditStore;

    @BeforeEach
    void setUp() {
        MailConfig.MailProperties mail = new MailConfig.MailProperties();
        mail.setProtocol("maildev");
        mail.setUsername("reader@example.com");
        mail.setPassword("top-secret");
        mail.setPollIntervalSeconds(60);

        MailConfig.PathProperties paths = new MailConfig.PathProperties();
        paths.setProcessed("mail/processed");
        paths.setDrafts("mail/drafts");

        MailConfig.ImapProperties imap = new MailConfig.ImapProperties();
        MailConfig.EwsProperties ews = new MailConfig.EwsProperties();
        MailConfig.FolderProperties folders = new MailConfig.FolderProperties();

        auditStore = mock(MailControlAuditStore.class);
        service = new MailRuntimeConfigService(mail, paths, imap, ews, folders, auditStore);
    }

    @Test
    void descriptorMasksSecretAndExposesRequiredSettings() {
        ControlSettingsResponse response = service.descriptor();

        assertEquals("mail", response.pluginCode());
        assertTrue(response.settings().containsKey("enabled"));
        assertEquals("*****", response.settings().get("password").value());
        assertEquals(List.of("maildev", "imap", "ews"), response.settings().get("protocol").options());
    }

    @Test
    void applyUpdatesRuntimeStateAndHidesPasswordFromResponse() {
        ControlSettingsStatusResponse response = service.apply(Map.of(
                "enabled", "false",
                "protocol", "ews",
                "password", "new-secret",
                "serverUrl", "https://exchange.example.com/EWS/Exchange.asmx",
                "foldersExclude", "Inbox/CI/CD\nJunk Email"
        ));

        assertEquals("APPLIED", response.status());
        assertEquals("false", response.applied().get("enabled"));
        assertEquals("ews", response.applied().get("protocol"));
        assertFalse(response.applied().containsKey("password"));
        assertEquals("secret value accepted but not returned", response.ignored().get("password"));

        ControlStatusResponse status = service.status();
        assertFalse(status.enabled());
        assertEquals("ews", status.protocol());

        verify(auditStore).save(eq(2L), any(), any(), any(), eq("APPLIED"), any());
    }
}
