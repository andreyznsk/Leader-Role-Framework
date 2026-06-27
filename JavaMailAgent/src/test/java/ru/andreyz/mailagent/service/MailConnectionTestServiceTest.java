package ru.andreyz.mailagent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.andreyz.mailagent.client.MailClient;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.MailConnectionErrorType;
import ru.andreyz.mailagent.model.MailEndpointDetectRequest;
import ru.andreyz.mailagent.model.MailEndpointDetectResult;
import ru.andreyz.mailagent.model.MailConnectionTestRequest;
import ru.andreyz.mailagent.model.MailConnectionTestResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MailConnectionTestServiceTest {

    private MailRuntimeConfigService runtimeConfigService;
    private MailConnectionTestService service;
    private CapturingEwsConnectionTester ewsConnectionTester;
    private StubEwsEndpointDetector endpointDetector;

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
        ews.setAuthType("NTLM");
        MailConfig.FolderProperties folders = new MailConfig.FolderProperties();
        MailPromptTemplateService promptTemplateService = mock(MailPromptTemplateService.class);
        when(promptTemplateService.loadClassificationPrompt()).thenReturn("Prompt");

        runtimeConfigService = new MailRuntimeConfigService(mail, paths, imap, ews, folders, mock(MailControlAuditStore.class), promptTemplateService);
        ewsConnectionTester = new CapturingEwsConnectionTester();
        endpointDetector = new StubEwsEndpointDetector();
        service = new MailConnectionTestService(
                runtimeConfigService,
                mock(MailClient.class),
                ews,
                imap,
                ewsConnectionTester,
                endpointDetector
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
                mock(MailControlAuditStore.class),
                promptTemplateService()
        );
        MailConnectionTestService blankUrlService = new MailConnectionTestService(
                blankUrlRuntime,
                mock(MailClient.class),
                ews,
                imap,
                ewsConnectionTester,
                endpointDetector
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

    private MailPromptTemplateService promptTemplateService() {
        MailPromptTemplateService service = mock(MailPromptTemplateService.class);
        when(service.loadClassificationPrompt()).thenReturn("Prompt");
        return service;
    }

    private static class StubEwsEndpointDetector extends EwsEndpointDetector {
        @Override
        public MailEndpointDetectResult detect(MailEndpointDetectRequest request) {
            String endpoint = request != null ? request.ewsUrl() : null;
            if (endpoint == null || endpoint.isBlank()) {
                return MailEndpointDetectResult.failed("ews", false, false, false, null,
                        MailConnectionErrorType.INVALID_ENDPOINT, "EWS endpoint is required", "Endpoint URL is blank", endpoint);
            }
            return MailEndpointDetectResult.detected("ews", true, true, true, 200, "NTLM",
                    "EWS endpoint detected", endpoint);
        }
    }
}
