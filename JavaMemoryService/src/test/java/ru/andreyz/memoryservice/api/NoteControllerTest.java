package ru.andreyz.memoryservice.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ru.andreyz.memoryservice.domain.Note;
import ru.andreyz.memoryservice.service.NoteService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NoteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NoteService noteService;

    @Test
    void createAndListNotes() throws Exception {
        mockMvc.perform(post("/api/notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Raw observation","text":"raw observation","tags":"risk,person","source":"capture"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value("Raw observation"))
                .andExpect(jsonPath("$.text").value("raw observation"))
                .andExpect(jsonPath("$.tags").value("risk,person"))
                .andExpect(jsonPath("$.source").value("capture"));

        mockMvc.perform(get("/api/notes").param("tags", "person").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.text == 'raw observation')]").exists());
    }

    @Test
    void deleteNoteRemovesItFromList() throws Exception {
        Note note = noteService.create("Delete me", "delete me", "ui,test", "manual-ui");

        mockMvc.perform(delete("/api/notes/{id}", note.id()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/notes").param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %s)]".formatted(note.id())).doesNotExist());
    }

    @Test
    void deleteMissingNoteReturnsNotFound() throws Exception {
        mockMvc.perform(delete("/api/notes/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createDerivesTitleWhenMissing() throws Exception {
        mockMvc.perform(post("/api/notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"derived title from body","tags":"risk","source":"capture"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("derived title from body"))
                .andExpect(jsonPath("$.text").value("derived title from body"));
    }
}
