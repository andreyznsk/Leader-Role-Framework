package ru.andreyz.mailagent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import ru.andreyz.mailagent.model.Email;
import ru.andreyz.mailagent.model.ProcessedEmail;
import ru.andreyz.mailagent.model.MailProcessingRoute;
import ru.andreyz.mailagent.repository.ProcessedEmailRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class MailProcessingStateService {

    private final ProcessedEmailRepository processedEmailRepository;
    private final ObjectMapper objectMapper;

    public MailProcessingStateService(ProcessedEmailRepository processedEmailRepository,
                                      ObjectMapper objectMapper) {
        this.processedEmailRepository = processedEmailRepository;
        this.objectMapper = objectMapper;
    }

    public ProcessedEmail start(Email email, String responseType) {
        return processedEmailRepository.findByEmailId(email.id())
            .orElseGet(() -> processedEmailRepository.save(
                ProcessedEmail.newProcessing(email, responseType, LocalDateTime.now())
            ));
    }

    public ProcessedEmail checkpoint(ProcessedEmail current,
                                     String responseType,
                                     MailProcessingRoute route,
                                     Object payload,
                                     String outputPath,
                                     String actionResultJson) {
        return processedEmailRepository.save(current.withCheckpoint(
            responseType,
            route,
            serialize(payload),
            outputPath,
            actionResultJson,
            LocalDateTime.now()
        ));
    }

    public ProcessedEmail markError(ProcessedEmail current, Exception exception) {
        return processedEmailRepository.save(current.withError(exception.getMessage(), LocalDateTime.now()));
    }

    public ProcessedEmail markProcessed(ProcessedEmail current, String outputPath, String actionResultJson) {
        return processedEmailRepository.save(current.withProcessed(outputPath, actionResultJson, LocalDateTime.now()));
    }

    public List<ProcessedEmail> findErrorQueue() {
        return processedEmailRepository.findByStatusOrderByLastAttemptAtAscCreatedAtAsc("ERROR");
    }

    public Optional<ProcessedEmail> findByEmailId(String emailId) {
        return processedEmailRepository.findByEmailId(emailId);
    }

    public <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize route payload for " + type.getSimpleName(), e);
        }
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize route payload", e);
        }
    }
}
