package ru.andreyz.memoryservice.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.domain.Question;
import ru.andreyz.memoryservice.service.QuestionService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/questions")
public class QuestionController {

    private final QuestionService questionService;

    public QuestionController(QuestionService questionService) {
        this.questionService = questionService;
    }

    @GetMapping
    public ResponseEntity<List<Question>> getQuestions(@RequestParam(name = "status", required = false) String status) {
        List<Question> questions = status != null
                ? questionService.findByStatus(status)
                : questionService.findAll();
        return ResponseEntity.ok(questions);
    }

    @PostMapping
    public ResponseEntity<Question> create(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(
                questionService.create(body.get("title"), body.get("context")));
    }
}
