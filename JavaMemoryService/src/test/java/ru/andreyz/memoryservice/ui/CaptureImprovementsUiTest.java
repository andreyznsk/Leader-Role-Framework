package ru.andreyz.memoryservice.ui;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ru.andreyz.memoryservice.service.NoteService;
import ru.andreyz.memoryservice.service.RiskService;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaptureImprovementsUiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NoteService noteService;

    @Autowired
    private RiskService riskService;

    @Test
    void notesPageRendersCreateTaskAction() throws Exception {
        noteService.create("Иван не знает процедуру rollback", "person,risk", "capture");

        mockMvc.perform(get("/ui/notes"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&rarr; В задачу")))
                .andExpect(content().string(containsString("taskFromItemModal")));
    }

    @Test
    void risksPageRendersCreateTaskAction() throws Exception {
        riskService.create("Только один человек знает деплой", "bus factor", "MEDIUM", "HIGH");

        mockMvc.perform(get("/ui/risks"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&rarr; В задачу")))
                .andExpect(content().string(containsString("taskFromRiskModal")));
    }
}
