package ru.andreyz.memoryservice.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void systemSettingsExposesProfileAndAgentProvider() throws Exception {
        mockMvc.perform(get("/api/settings/system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("java-memory-service"))
                .andExpect(jsonPath("$.activeProfiles[0]").value("test"))
                .andExpect(jsonPath("$.agentProvider").value("mock"))
                .andExpect(jsonPath("$.registeredPlugins[?(@.code == 'mail')]").exists());
    }

    @Test
    void mailPluginSettingsCanBeSavedAndSecretsAreMasked() throws Exception {
        mockMvc.perform(put("/api/settings/plugins/mail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "enabled": true,
                                  "config": {
                                    "protocol": "ews",
                                    "login": "user@example.com",
                                    "password": "plain-value-only-on-write",
                                    "serverUrl": "https://exchange.example.com/EWS/Exchange.asmx",
                                    "port": 443,
                                    "ssl": true,
                                    "pollIntervalSeconds": 90,
                                    "foldersInclude": ["Inbox"],
                                    "foldersExclude": ["Junk Email"],
                                    "markNoiseAsRead": true,
                                    "moveProcessed": true,
                                    "processedFolder": "Processed",
                                    "draftFolder": "Drafts"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.config.passwordConfigured").value(true))
                .andExpect(jsonPath("$.config.passwordMasked").value("********"))
                .andExpect(content().string(not(containsString("plain-value-only-on-write"))));

        mockMvc.perform(get("/api/settings/plugins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'mail')].enabled").value(true));

        mockMvc.perform(get("/api/settings/plugins/mail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config.login").value("user@example.com"))
                .andExpect(jsonPath("$.config.passwordMasked").value("********"))
                .andExpect(content().string(not(containsString("plain-value-only-on-write"))));

        mockMvc.perform(get("/api/plugins/mail/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protocol").value("ews"))
                .andExpect(jsonPath("$.passwordConfigured").value(true))
                .andExpect(jsonPath("$.passwordMasked").value("********"))
                .andExpect(content().string(not(containsString("plain-value-only-on-write"))));
    }

    @Test
    void heartbeatUpdatesPluginStatus() throws Exception {
        mockMvc.perform(post("/api/plugins/mail/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"UP","message":"poller running"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("mail"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.lastHeartbeatAt").isString());

        mockMvc.perform(get("/api/settings/plugins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'mail')].status").value("UP"));
    }

    @Test
    void testMailConnectionReturnsFailureWhenMailAgentUnavailable() throws Exception {
        mockMvc.perform(post("/api/settings/plugins/mail/test-connection"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isString());
    }
}
