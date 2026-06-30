package ru.andreyz.mailagent.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.integration.MemoryServiceClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Controller
public class StatusController {


    private final MailConfig.PathProperties pathProperties;
    private final MemoryServiceClient memoryServiceClient;
    private final String logFile;

    public StatusController(
        MailConfig.PathProperties pathProperties,
        MemoryServiceClient memoryServiceClient,
        @Value("${logging.file.name:logs/mail-agent.log}") String logFile
    ) {
        this.pathProperties = pathProperties;
        this.memoryServiceClient = memoryServiceClient;
        this.logFile = logFile;
    }

    @GetMapping("/ui/status")
    public String status(Model model) {
        model.addAttribute("inboxCount", countFiles(pathProperties.getInbox()));
        model.addAttribute("processedCount", countFiles(pathProperties.getProcessed()));
        model.addAttribute("draftsCount", countFiles(pathProperties.getDrafts()));
        model.addAttribute("recentLogs", readRecentLogs(50));
        model.addAttribute("memoryServiceHealthy", memoryServiceClient.isHealthy());
        return "status";
    }

    private long countFiles(String dir) {
        Path path = Path.of(dir);
        if (!Files.exists(path)) return 0;
        try (Stream<Path> files = Files.list(path)) {
            return files.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            log.error("", e);
            return 0;
        }
    }

    private List<String> readRecentLogs(int lines) {
        Path logPath = Path.of(logFile);
        if (!Files.exists(logPath)) return List.of("No log file yet");
        try {
            List<String> all = Files.readAllLines(logPath);
            int start = Math.max(0, all.size() - lines);
            return all.subList(start, all.size());
        } catch (IOException e) {
            log.error("", e);
            return List.of("Error reading log: " + e.getMessage());
        }
    }
}
