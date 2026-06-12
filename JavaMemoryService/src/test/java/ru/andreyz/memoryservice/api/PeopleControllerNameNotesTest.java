package ru.andreyz.memoryservice.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PeopleControllerNameNotesTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void postNoteByName_returnsCreatedNote() throws Exception {
        mockMvc.perform(post("/api/people/name/Петр/notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note": "Хочет перейти в другую команду"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.personName").value("Петр"))
                .andExpect(jsonPath("$.note").value("Хочет перейти в другую команду"))
                .andExpect(jsonPath("$.createdAt").isString());
    }

    @Test
    void getNotesByName_returnsSavedNotes() throws Exception {
        String uniqueName = "UniqueTestPerson123";

        mockMvc.perform(post("/api/people/name/" + uniqueName + "/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"note": "first note about this person"}
                        """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/people/name/" + uniqueName + "/notes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.note == 'first note about this person')]").exists());
    }

    @Test
    void getNotesByName_caseInsensitive() throws Exception {
        mockMvc.perform(post("/api/people/name/Иван/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"note": "case insensitive note"}
                        """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/people/name/иван/notes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.note == 'case insensitive note')]").exists());
    }

    @Test
    void getNotesByName_differentPerson_returnsEmpty() throws Exception {
        mockMvc.perform(get("/api/people/name/PersonWhoDoeNotExistXYZ/notes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void postMultipleNotes_allReturnedForSamePerson() throws Exception {
        String person = "MultiNoteTestPerson";

        mockMvc.perform(post("/api/people/name/" + person + "/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\": \"note one\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/people/name/" + person + "/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\": \"note two\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/people/name/" + person + "/notes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.note == 'note one')]").exists())
                .andExpect(jsonPath("$[?(@.note == 'note two')]").exists());
    }
}
