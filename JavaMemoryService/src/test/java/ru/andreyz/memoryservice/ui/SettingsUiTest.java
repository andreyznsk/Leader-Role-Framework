package ru.andreyz.memoryservice.ui;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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

    @Test
    void settingsPageRendersSystemAndMailBlocks() throws Exception {
        mockMvc.perform(get("/ui/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Settings")))
                .andExpect(content().string(containsString("Active Spring profile")))
                .andExpect(content().string(containsString("mock")))
                .andExpect(content().string(containsString("Mail Plugin Settings")));
    }

    @Test
    void saveMailSettingsMasksSecretOnUi() throws Exception {
        mockMvc.perform(post("/ui/settings/plugins/mail")
                        .param("enabled", "true")
                        .param("protocol", "imap")
                        .param("login", "leader@example.com")
                        .param("password", "ui-secret")
                        .param("host", "imap.example.com")
                        .param("port", "993")
                        .param("ssl", "true")
                        .param("pollIntervalSeconds", "45")
                        .param("foldersExclude", "Junk\nSpam"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings?plugin=mail&saved=1"));

        mockMvc.perform(get("/ui/settings?plugin=mail&saved=1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Mail plugin settings saved.")))
                .andExpect(content().string(containsString("Stored value: ********")))
                .andExpect(content().string(not(containsString("ui-secret"))));
    }

    @Test
    void testConnectionShowsResultBanner() throws Exception {
        mockMvc.perform(post("/ui/settings/plugins/mail/test-connection"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/ui/settings?plugin=mail&testSuccess=*&testMessage=*"));

        mockMvc.perform(get("/ui/settings?plugin=mail&testSuccess=0&testMessage=MailAgent+offline"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("MailAgent+offline")))
                .andExpect(content().string(containsString("Test Connection")));
    }
}
