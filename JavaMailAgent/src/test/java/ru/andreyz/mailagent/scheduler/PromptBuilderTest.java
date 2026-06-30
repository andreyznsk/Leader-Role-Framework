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
            true,
            "processed",
            "drafts",
            "From={{from}}\nTo={{recipients}}\nSubject={{subject}}\nMessageId={{messageId}}\nConversationId={{conversationId}}\nInReplyTo={{inReplyTo}}\nBody={{body}}\nId={{emailId}}",
            "Link={{context}}",
            1L,
            LocalDateTime.of(2026, 6, 26, 12, 0)
        ));

        PromptBuilder promptBuilder = new PromptBuilder(runtimeConfigService);
        Email email = new Email(
            "msg-001",
            "Subject line",
            "sender@example.com",
            List.of("one@example.com", "two@example.com"),
            "Body text",
            "<message-001@example.com>",
            "conv-001",
            "<parent-001@example.com>",
            LocalDateTime.now(),
            "INBOX"
        );

        String prompt = promptBuilder.build(email);

        assertEquals("""
            From=sender@example.com
            To=one@example.com, two@example.com
            Subject=Subject line
            MessageId=<message-001@example.com>
            ConversationId=conv-001
            InReplyTo=<parent-001@example.com>
            Body=Body text
            Id=msg-001""", prompt);
    }
}
