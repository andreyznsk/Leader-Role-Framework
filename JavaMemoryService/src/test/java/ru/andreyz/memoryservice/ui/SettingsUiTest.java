package ru.andreyz.memoryservice.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ru.andreyz.memoryservice.support.ControlPluginStubServers;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SettingsUiTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void startStubs() throws Exception {
        ControlPluginStubServers.ensureStarted();
    }

    @BeforeEach
    void resetStubs() {
        ControlPluginStubServers.reset();
    }

    @Test
    void settingsPageRendersUniversalMailAndRagBlocks() throws Exception {
        mockMvc.perform(get("/ui/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Registered Control Plugins")))
                .andExpect(content().string(containsString("Mail Agent")))
                .andExpect(content().string(containsString("RAG Service")))
                .andExpect(content().string(containsString("data-bs-target=\"#plugin-body-mail\"")))
                .andExpect(content().string(containsString("data-bs-target=\"#plugin-body-rag\"")))
                .andExpect(content().string(containsString("data-plugin-field=\"login\"")))
                .andExpect(content().string(containsString("data-plugin-field=\"password\"")))
                .andExpect(content().string(containsString("data-plugin-field=\"serverUrl\"")))
                .andExpect(content().string(containsString("data-plugin-field=\"authType\"")))
                .andExpect(content().string(containsString("data-plugin-field=\"host\"")))
                .andExpect(content().string(containsString("data-plugin-field=\"port\"")))
                .andExpect(content().string(containsString("data-protocol-scope=\"ews\"")))
                .andExpect(content().string(containsString("data-protocol-scope=\"imap\"")))
                .andExpect(content().string(containsString("updateProtocolScopedFields")))
                .andExpect(content().string(containsString("Detect Endpoint")))
                .andExpect(content().string(containsString("Test Connection")))
                .andExpect(content().string(containsString("data-detect-url=\"/api/settings/control/plugins/mail/detect-endpoint\"")))
                .andExpect(content().string(containsString("data-test-url=\"/api/settings/control/plugins/mail/test-connection\"")))
                .andExpect(content().string(containsString("mail-test-connection-modal")))
                .andExpect(content().string(containsString("data-mail-test-modal-title")))
                .andExpect(content().string(containsString("data-mail-test-modal-message")))
                .andExpect(content().string(containsString("Success")))
                .andExpect(content().string(containsString("Fail")))
                .andExpect(content().string(containsString("Used for Exchange EWS only")))
                .andExpect(content().string(containsString("Used for IMAP only")))
                .andExpect(content().string(containsString("Folders exclude")))
                .andExpect(content().string(containsString("Scan interval seconds")));
    }

    @Test
    void saveMailSettingsUsesUniversalForm() throws Exception {
        mockMvc.perform(post("/ui/settings/plugins/mail")
                        .param("settings[enabled]", "false")
                        .param("settings[protocol]", "ews")
                        .param("settings[foldersExclude]", "Inbox/CI/CD\nJunk Email"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings?plugin=mail&saved=mail"));

        mockMvc.perform(get("/ui/settings?plugin=mail&saved=mail"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("settings applied.")))
                .andExpect(content().string(containsString("Mail Agent")));
    }

    @Test
    void unavailablePluginShowsWarningWithoutBreakingPage() throws Exception {
        mockMvc.perform(get("/ui/settings?plugin=rag"))
                .andExpect(status().isOk());

        ControlPluginStubServers.setRagUnavailable(true);

        mockMvc.perform(get("/ui/settings?plugin=rag"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("DOWN")))
                .andExpect(content().string(containsString("RAG control API unavailable")))
                .andExpect(content().string(containsString("Rendering last synced snapshot")));
    }
}
