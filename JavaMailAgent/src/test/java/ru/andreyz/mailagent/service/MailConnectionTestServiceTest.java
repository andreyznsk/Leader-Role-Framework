package ru.andreyz.mailagent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.andreyz.mailagent.client.MailClient;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.MailAuthType;
import ru.andreyz.mailagent.model.MailConnectionErrorType;
import ru.andreyz.mailagent.model.MailConnectionTestRequest;
import ru.andreyz.mailagent.model.MailConnectionTestResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class MailConnectionTestServiceTest {

    private MailRuntimeConfigService runtimeConfigService;
    private MailConnectionTestService service;
    private CapturingEwsConnectionTester ewsConnectionTester;

    @BeforeEach
    void setUp() {
        MailConfig.MailProperties mail = new MailConfig.MailProperties();
        mail.setProtocol("ews");
        mail.setUsername("reader@example.com");
        mail.setPassword("top-secret");

        MailConfig.PathProperties paths = new MailConfig.PathProperties();
        MailConfig.ImapProperties imap = new MailConfig.ImapProperties();
        MailConfig.EwsProperties ews = new MailConfig.EwsProperties();
        ews.setUrl("https://exchange.example.com/EWS/Exchange.asmx");
        ews.setAuthType("BASIC");
        MailConfig.FolderProperties folders = new MailConfig.FolderProperties();

        runtimeConfigService = new MailRuntimeConfigService(mail, paths, imap, ews, folders, mock(MailControlAuditStore.class));
        ewsConnectionTester = new CapturingEwsConnectionTester();
        service = new MailConnectionTestService(
                runtimeConfigService,
                mock(MailClient.class),
                ews,
                imap,
                ewsConnectionTester
        );
    }

    @Test
    void returnsFailedWhenEwsUrlIsBlank() {
        MailConfig.MailProperties mail = new MailConfig.MailProperties();
        mail.setProtocol("ews");
        mail.setUsername("reader@example.com");
        mail.setPassword("top-secret");
        MailConfig.ImapProperties imap = new MailConfig.ImapProperties();
        MailConfig.EwsProperties ews = new MailConfig.EwsProperties();
        ews.setUrl("");
        MailRuntimeConfigService blankUrlRuntime = new MailRuntimeConfigService(
                mail,
                new MailConfig.PathProperties(),
                imap,
                ews,
                new MailConfig.FolderProperties(),
                mock(MailControlAuditStore.class)
        );
        MailConnectionTestService blankUrlService = new MailConnectionTestService(
                blankUrlRuntime,
                mock(MailClient.class),
                ews,
                imap,
                ewsConnectionTester
        );

        MailConnectionTestResult result = blankUrlService.testConnection(new MailConnectionTestRequest(
                "ews",
                null,
                "reader@example.com",
                "secret",
                "BASIC",
                null,
                null,
                null,
                List.of()
        ));

        assertEquals(false, result.success());
        assertEquals("INVALID_ENDPOINT", result.errorType());
    }

    @Test
    void mergesRuntimePasswordAndAuthTypeForEwsTest() {
        MailConnectionTestResult result = service.testConnection(new MailConnectionTestRequest(
                "ews",
                "https://exchange.example.com/EWS/Exchange.asmx",
                "reader@example.com",
                "",
                "NTLM",
                null,
                null,
                null,
                List.of("Inbox/CI/CD")
        ));

        assertEquals(true, result.success());
        assertEquals("NTLM", ewsConnectionTester.lastAuthType);
        assertEquals("top-secret", ewsConnectionTester.lastPassword);
        assertEquals(List.of("Inbox/CI/CD"), ewsConnectionTester.lastFoldersExclude);
    }

    private static class CapturingEwsConnectionTester extends EwsConnectionTester {
        private String lastAuthType;
        private String lastPassword;
        private List<String> lastFoldersExclude;

        private CapturingEwsConnectionTester() {
            super(new MailConfig.TestConnectionProperties());
        }

        @Override
        public MailConnectionTestResult test(MailConfig.MailProperties mailProperties,
                                             MailConfig.EwsProperties ewsProperties,
                                             List<String> excludeFolders) {
            this.lastAuthType = ewsProperties.getAuthType();
            this.lastPassword = mailProperties.getPassword();
            this.lastFoldersExclude = excludeFolders;
            if (ewsProperties.getUrl() == null || ewsProperties.getUrl().isBlank()) {
                return MailConnectionTestResult.failed("ews", ewsProperties.getAuthType(),
                        MailConnectionErrorType.INVALID_ENDPOINT, "EWS endpoint is invalid", "Endpoint URL is blank", null);
            }
            return MailConnectionTestResult.connected("ews", "Exchange2010_SP2", ewsProperties.getAuthType(),
                    1, false, true, "Connected", ewsProperties.getUrl());
        }
    }
}
