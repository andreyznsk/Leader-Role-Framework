package ru.andreyz.memoryservice.ui;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import ru.andreyz.memoryservice.service.IntakeService;
import ru.andreyz.memoryservice.service.KnowledgeService;
import ru.andreyz.memoryservice.service.NoteService;
import ru.andreyz.memoryservice.service.TaskService;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {
        NotesViewController.class,
        KnowledgeViewController.class,
        NoticeRedirectController.class
})
@Import(UiNavigationModelAdvice.class)
class MemoryNavigationUiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private KnowledgeService knowledgeService;

    @MockBean
    private NoteService noteService;

    @MockBean
    private TaskService taskService;

    @MockBean
    private IntakeService intakeService;

    @Test
    void notesPageUsesOperationalTitle() throws Exception {
        when(noteService.list(null, 50)).thenReturn(List.of());
        when(taskService.findPending()).thenReturn(List.of());
        when(intakeService.countNew()).thenReturn(0);
        mockMvc.perform(get("/ui/notes"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Operational Notes")))
                .andExpect(content().string(containsString("Operational Memory")));
    }

    @Test
    void knowledgePageRendersRagKnowledge() throws Exception {
        when(knowledgeService.list(null)).thenReturn(List.of());
        when(taskService.findPending()).thenReturn(List.of());
        when(intakeService.countNew()).thenReturn(0);

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

    @Test
    void notesPageShowsIntakeAlertDotWhenNewItemsExist() throws Exception {
        when(noteService.list(null, 50)).thenReturn(List.of());
        when(taskService.findPending()).thenReturn(List.of());
        when(intakeService.countNew()).thenReturn(2);

        mockMvc.perform(get("/ui/notes"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Есть новые intake items: 2")));
    }
}
