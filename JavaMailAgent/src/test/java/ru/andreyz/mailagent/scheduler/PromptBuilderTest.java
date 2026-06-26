package ru.andreyz.mailagent.scheduler;

import org.junit.jupiter.api.Test;
import ru.andreyz.mailagent.model.Email;
import ru.andreyz.mailagent.model.MailAuthType;
import ru.andreyz.mailagent.service.MailRuntimeConfig;
import ru.andreyz.mailagent.service.MailRuntimeConfigService;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromptBuilderTest {

    @Test
    void buildUsesRuntimePromptTemplateFromConfig() {
        MailRuntimeConfigService runtimeConfigService = mock(MailRuntimeConfigService.class);
        when(runtimeConfigService.snapshot()).thenReturn(new MailRuntimeConfig(
            true,
            "maildev",
            "",
            null,
            "",
            MailAuthType.BASIC,
            "",
            0,
            false,
            60,
            List.of("Inbox"),
            List.of(),
            true,
            true,
            "processed",
            "drafts",
            "From={{from}}\nSubject={{subject}}\nBody={{body}}\nId={{emailId}}",
            1L,
            LocalDateTime.of(2026, 6, 26, 12, 0)
        ));

        PromptBuilder promptBuilder = new PromptBuilder(runtimeConfigService);
        Email email = new Email("msg-001", "Subject line", "sender@example.com", "Body text", LocalDateTime.now(), "INBOX");

        String prompt = promptBuilder.build(email);

        assertEquals("From=sender@example.com\nSubject=Subject line\nBody=Body text\nId=msg-001", prompt);
    }
}
