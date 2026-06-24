package ru.andreyz.memoryservice.api;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ru.andreyz.memoryservice.support.ControlPluginStubServers;

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

    @BeforeAll
    static void startStubs() throws Exception {
        ControlPluginStubServers.ensureStarted();
    }

    @BeforeEach
    void resetStubs() {
        ControlPluginStubServers.reset();
    }

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
    void controlPluginEndpointsReturnMailAndRagDescriptors() throws Exception {
        mockMvc.perform(get("/api/settings/control/plugins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'mail')]").exists())
                .andExpect(jsonPath("$[?(@.code == 'mail')].status").value(org.hamcrest.Matchers.hasItem("UP")))
                .andExpect(jsonPath("$[?(@.code == 'rag')]").exists())
                .andExpect(jsonPath("$[?(@.code == 'rag')].status").value(org.hamcrest.Matchers.hasItem("UP")));

        mockMvc.perform(get("/api/settings/control/plugins/mail/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pluginCode").value("mail"))
                .andExpect(jsonPath("$.settings.protocol.options[0]").value("maildev"))
                .andExpect(jsonPath("$.settings.authType.options[1]").value("NTLM"));

        mockMvc.perform(get("/api/settings/control/plugins/rag/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pluginCode").value("rag"))
                .andExpect(jsonPath("$.settings.scanIntervalSeconds.type").value("number"));
    }

    @Test
    void controlPluginEndpointsApplyMailAndRagSettings() throws Exception {
        mockMvc.perform(put("/api/settings/control/plugins/mail/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "settings": {
                                    "enabled": "false",
                                    "protocol": "ews"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pluginCode").value("mail"))
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.applied.protocol").value("ews"));

        mockMvc.perform(put("/api/settings/control/plugins/rag/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "settings": {
                                    "enabled": "true",
                                    "scanIntervalSeconds": "120"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pluginCode").value("rag"))
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.applied.scanIntervalSeconds").value("120"));

        mockMvc.perform(get("/api/settings/control/plugins/mail/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("APPLIED"));
    }

    @Test
    void unavailableControlPluginReturnsServiceUnavailable() throws Exception {
        ControlPluginStubServers.setRagUnavailable(true);

        mockMvc.perform(get("/api/settings/control/plugins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'rag')].status").value(org.hamcrest.Matchers.hasItem("DOWN")));

        mockMvc.perform(get("/api/settings/control/plugins/rag/settings"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.pluginCode").value("rag"))
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(containsString("Plugin settings fetch failed")));
    }

    @Test
    void legacyMailPluginSettingsCanStillBeSavedAndSecretsAreMasked() throws Exception {
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
                .andExpect(jsonPath("$.config.passwordMasked").value("*****"))
                .andExpect(content().string(not(containsString("plain-value-only-on-write"))));
    }

    @Test
    void legacyTestMailConnectionUsesStubbedMailAgent() throws Exception {
        mockMvc.perform(post("/api/settings/control/plugins/mail/test-connection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "protocol": "ews",
                                  "ewsUrl": "https://exchange.example.com/EWS/Exchange.asmx",
                                  "username": "reader@example.com",
                                  "authType": "NTLM",
                                  "folderExclude": ["Inbox/CI/CD"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value("CONNECTED"))
                .andExpect(jsonPath("$.authType").value("NTLM"))
                .andExpect(jsonPath("$.foldersFound").value(127))
                .andExpect(jsonPath("$.message").value("Connected"));
    }
}
