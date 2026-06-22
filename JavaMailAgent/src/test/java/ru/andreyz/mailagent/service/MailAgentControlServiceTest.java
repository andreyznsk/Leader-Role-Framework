package ru.andreyz.mailagent.service;

import org.junit.jupiter.api.Test;
import ru.andreyz.mailagent.client.MailClient;
import ru.andreyz.mailagent.model.MailConnectionTestResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MailAgentControlServiceTest {

    @Test
    void testConnectionDelegatesToMailClient() {
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
                return new MailConnectionTestResult(true, "ok", "maildev");
            }

            @Override
            public void close() {
            }
        };

        MailAgentControlService service = new MailAgentControlService(mailClient);
        MailConnectionTestResult result = service.testConnection();

        assertEquals(true, result.success());
        assertEquals("ok", result.message());
        assertEquals("maildev", result.target());
    }
}
