package ru.andreyz.memoryservice.ui;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ru.andreyz.memoryservice.service.KnowledgeService;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemoryNavigationUiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private KnowledgeService knowledgeService;

    @Test
    void notesPageUsesOperationalTitle() throws Exception {
        mockMvc.perform(get("/ui/notes"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Operational Notes")))
                .andExpect(content().string(containsString("Operational Memory")));
    }

    @Test
    void knowledgePageRendersRagKnowledge() throws Exception {
        when(knowledgeService.list(null)).thenReturn(List.of());

        mockMvc.perform(get("/ui/knowledge"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("RAG Knowledge")))
                .andExpect(content().string(containsString("Knowledge Gateway")))
                .andExpect(content().string(containsString("Notices")))
                .andExpect(content().string(containsString("Service Cards")));
    }

    @Test
    void noticeRedirectsToKnowledgeNoticeFilter() throws Exception {
        mockMvc.perform(get("/ui/notice"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/knowledge?type=NOTICE"));
    }
}
