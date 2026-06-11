package ru.andreyz.memoryservice.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.andreyz.memoryservice.domain.Question;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class QuestionServiceTest {

    @Autowired
    private QuestionService questionService;

    @Test
    void create_hasOpenStatusAndTimestamp() {
        Question q = questionService.create("How does circuit breaker work?", "need for review");

        assertThat(q.id()).isNotNull();
        assertThat(q.title()).isEqualTo("How does circuit breaker work?");
        assertThat(q.context()).isEqualTo("need for review");
        assertThat(q.status()).isEqualTo("OPEN");
        assertThat(q.createdAt()).isNotNull();
    }

    @Test
    void create_withNullContext() {
        Question q = questionService.create("Quick question", null);

        assertThat(q.id()).isNotNull();
        assertThat(q.context()).isNull();
    }

    @Test
    void findAll_includesCreatedQuestion() {
        questionService.create("findAll test question", null);

        List<Question> all = questionService.findAll();

        assertThat(all).anySatisfy(q -> assertThat(q.title()).isEqualTo("findAll test question"));
    }

    @Test
    void findByStatus_returnsOnlyMatchingStatus() {
        questionService.create("open question for filter", null);

        List<Question> open = questionService.findByStatus("OPEN");

        assertThat(open).allMatch(q -> q.status().equals("OPEN"));
        assertThat(open).anySatisfy(q -> assertThat(q.title()).isEqualTo("open question for filter"));
    }

    @Test
    void findByStatus_unknownStatus_returnsEmpty() {
        List<Question> result = questionService.findByStatus("RESOLVED_NONEXISTENT");

        assertThat(result).isEmpty();
    }
}
