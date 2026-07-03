package ru.andreyz.memoryservice.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskAttachmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private long createTask() throws Exception {
        String today = LocalDate.now().toString();
        String created = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Attachment task","date":"%s","priority":"NORMAL","source":"MANUAL"}
                                """.formatted(today)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    void uploadFileThenDownloadThenDelete() throws Exception {
        long taskId = createTask();
        byte[] pngBytes = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        MockMultipartFile file = new MockMultipartFile("file", "screenshot.png", "image/png", pngBytes);

        String created = mockMvc.perform(multipart("/api/tasks/{id}/attachments", taskId).file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("FILE"))
                .andExpect(jsonPath("$.filename").value("screenshot.png"))
                .andExpect(jsonPath("$.mimeType").value("image/png"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long attachmentId = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mockMvc.perform(get("/api/tasks/{id}/attachments/{aid}/content", taskId, attachmentId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("image/png")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("inline")));

        mockMvc.perform(get("/api/tasks/{id}/attachments", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(attachmentId));

        mockMvc.perform(delete("/api/tasks/{id}/attachments/{aid}", taskId, attachmentId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/tasks/{id}/attachments", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void addLinkAttachment() throws Exception {
        long taskId = createTask();

        mockMvc.perform(post("/api/tasks/{id}/attachments/link", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://drive.google.com/file/d/abc123","title":"Design doc"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("LINK"))
                .andExpect(jsonPath("$.url").value("https://drive.google.com/file/d/abc123"))
                .andExpect(jsonPath("$.title").value("Design doc"));
    }

    @Test
    void rejectsPathTraversalFilename() throws Exception {
        long taskId = createTask();
        MockMultipartFile file = new MockMultipartFile("file", "../../evil.sh", "text/plain", "x".getBytes());

        mockMvc.perform(multipart("/api/tasks/{id}/attachments", taskId).file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsDisallowedMimeType() throws Exception {
        long taskId = createTask();
        MockMultipartFile file = new MockMultipartFile("file", "app.exe", "application/x-msdownload", "x".getBytes());

        mockMvc.perform(multipart("/api/tasks/{id}/attachments", taskId).file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsOversizedUpload() throws Exception {
        long taskId = createTask();
        byte[] oversized = new byte[21 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", oversized);

        mockMvc.perform(multipart("/api/tasks/{id}/attachments", taskId).file(file))
                .andExpect(status().isPayloadTooLarge());
    }
}
