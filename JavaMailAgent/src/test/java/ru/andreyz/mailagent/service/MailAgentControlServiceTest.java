package ru.andreyz.mailagent.service;

import org.junit.jupiter.api.Test;
import ru.andreyz.mailagent.client.MailClient;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.MailConnectionTestRequest;
import ru.andreyz.mailagent.model.MailConnectionTestResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MailAgentControlServiceTest {

    @Test
    void testConnectionDelegatesToMailConnectionTestService() {
        MailClient mailClient = new MailClient() {
            @Override
            public List<String> listFolders(List<String> excludeFolders) {
                return List.of();
            }

            @Override
            public List<ru.andreyz.mailagent.model.Email> listUnread(String folder, int limit) {
                return List.of();
            }

            @Override
            public void markAsRead(String emailId, String folder) {
            }

            @Override
            public MailConnectionTestResult testConnection() {
                return MailConnectionTestResult.connected("maildev", null, null, null, false, false, "ok", "maildev");
            }

            @Override
            public void close() {
            }
        };

        MailRuntimeConfigService runtimeConfigService = new MailRuntimeConfigService(
                new MailConfig.MailProperties(),
                new MailConfig.PathProperties(),
                new MailConfig.ImapProperties(),
                new MailConfig.EwsProperties(),
                new MailConfig.FolderProperties(),
                mock(MailControlAuditStore.class),
                promptTemplateService()
        );
        MailConnectionTestService connectionTestService = mock(MailConnectionTestService.class);
        when(connectionTestService.testConnection(null))
                .thenReturn(MailConnectionTestResult.connected("maildev", null, null, null, false, false, "ok", "maildev"));
        MailAgentControlService service = new MailAgentControlService(mailClient, runtimeConfigService, connectionTestService);
        MailConnectionTestResult result = service.testConnection((MailConnectionTestRequest) null);

        assertEquals(true, result.success());
        assertEquals("ok", result.message());
        assertEquals("maildev", result.target());
    }

    private MailPromptTemplateService promptTemplateService() {
        MailPromptTemplateService service = mock(MailPromptTemplateService.class);
        when(service.loadClassificationPrompt()).thenReturn("Prompt");
        when(service.saveClassificationPrompt(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(invocation -> invocation.getArgument(0));
        return service;
    }
}
