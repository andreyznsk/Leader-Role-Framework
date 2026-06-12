package ru.andreyz.memoryservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.Capture;
import ru.andreyz.memoryservice.dto.ClassifiedCapture;

import java.util.List;
import java.util.Locale;

@Service
@ConditionalOnProperty(name = "mock.capture-agent", havingValue = "true")
public class MockCaptureClassifierAgent extends CaptureClassifierAgent {

    private static final Logger log = LoggerFactory.getLogger(MockCaptureClassifierAgent.class);

    public MockCaptureClassifierAgent(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @PostConstruct
    public void init() {
        log.warn("MOCK CaptureClassifierAgent is active - real Claude agent will NOT be called");
        log.warn("Set mock.capture-agent=false to use real Claude agent");
    }

    @Override
    public List<ClassifiedCapture> classify(List<Capture> captures) {
        return captures.stream()
                .map(capture -> classifyText(capture.id(), null, capture.rawText()))
                .toList();
    }

    @Override
    public List<ClassifiedCapture> classifyFiles(List<CaptureService.CaptureFile> files, String dayContext) {
        return files.stream()
                .map(file -> classifyText(null, file.file(), file.text()))
                .toList();
    }

    private ClassifiedCapture classifyText(Long captureId, String file, String text) {
        CaptureType type = detectType(text);
        ParsedText parsed = parseText(type, text);
        String priority = detectPriority(text);
        String tags = type == CaptureType.NOTE ? "capture,mock" : null;

        log.debug("MockCaptureClassifierAgent: file={}, captureId={}, type={}, title={}",
                file, captureId, type, parsed.title());

        return new ClassifiedCapture(
                captureId,
                file,
                type.name(),
                parsed.title(),
                parsed.body(),
                tags,
                priority
        );
    }

    private CaptureType detectType(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        for (CaptureType type : CaptureType.values()) {
            if (upper.startsWith(type.name() + ":") ||
                    upper.startsWith("TYPE=" + type.name()) ||
                    upper.contains("[" + type.name() + "]")) {
                return type;
            }
        }
        if (upper.contains("РИСК") || upper.contains("RISK")) {
            return CaptureType.RISK;
        }
        if (upper.contains("ВОПРОС") || upper.contains("QUESTION")) {
            return CaptureType.QUESTION;
        }
        if (upper.contains("ЗАМЕТКА") || upper.contains("NOTE")) {
            return CaptureType.NOTE;
        }
        return CaptureType.TASK;
    }

    private ParsedText parseText(CaptureType type, String text) {
        String cleaned = stripTypeMarker(type, text).trim();
        String title = cleaned;
        String body = cleaned;

        int separator = cleaned.indexOf('|');
        if (separator >= 0) {
            title = cleaned.substring(0, separator).trim();
            body = cleaned.substring(separator + 1).trim();
        }

        if (title.isBlank()) {
            title = type.name() + " capture";
        }
        if (body.isBlank()) {
            body = title;
        }

        return new ParsedText(title, body);
    }

    private String stripTypeMarker(CaptureType type, String text) {
        String trimmed = text.trim();
        String prefix = type.name() + ":";
        if (trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return trimmed.substring(prefix.length());
        }

        prefix = "TYPE=" + type.name();
        if (trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
            String rest = trimmed.substring(prefix.length()).trim();
            if (rest.startsWith(":")) {
                return rest.substring(1);
            }
            return rest;
        }

        String bracket = "[" + type.name() + "]";
        if (trimmed.regionMatches(true, 0, bracket, 0, bracket.length())) {
            return trimmed.substring(bracket.length());
        }

        return trimmed;
    }

    private String detectPriority(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.contains("CRITICAL") || upper.contains("P0") || upper.contains("СРОЧНО")) {
            return "CRITICAL";
        }
        if (upper.contains("HIGH") || upper.contains("P1") || upper.contains("ВАЖНО")) {
            return "HIGH";
        }
        if (upper.contains("LOW") || upper.contains("P3")) {
            return "LOW";
        }
        return "NORMAL";
    }

    private enum CaptureType {
        TASK,
        RISK,
        NOTE,
        QUESTION,
        PERSON_NOTE,
        KNOWLEDGE,
        JOURNAL
    }

    private record ParsedText(String title, String body) {}
}
