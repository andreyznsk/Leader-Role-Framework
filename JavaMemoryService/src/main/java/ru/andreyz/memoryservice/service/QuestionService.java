package ru.andreyz.memoryservice.service;

import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.Question;
import ru.andreyz.memoryservice.repository.QuestionRepository;

import java.time.Instant;
import java.util.List;

@Service
public class QuestionService {

    private final QuestionRepository questionRepository;

    public QuestionService(QuestionRepository questionRepository) {
        this.questionRepository = questionRepository;
    }

    public Question create(String title, String context) {
        Question q = new Question(null, title, context, "OPEN", Instant.now());
        return questionRepository.save(q);
    }

    public List<Question> findAll() {
        return (List<Question>) questionRepository.findAll();
    }

    public List<Question> findByStatus(String status) {
        return questionRepository.findByStatus(status);
    }
}
