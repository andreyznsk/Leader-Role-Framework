package ru.andreyz.memoryservice.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.domain.Risk;
import ru.andreyz.memoryservice.service.RiskService;

import java.util.List;

@RestController
@RequestMapping("/api/risks")
public class RiskController {

    private final RiskService riskService;

    public RiskController(RiskService riskService) {
        this.riskService = riskService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Risk> getById(@PathVariable Long id) {
        return riskService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        riskService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<List<Risk>> getRisks(@RequestParam(required = false) String status) {
        List<Risk> risks = status != null
                ? riskService.findByStatus(status)
                : riskService.findAll();
        return ResponseEntity.ok(risks);
    }

    @PostMapping
    public ResponseEntity<Risk> create(@RequestBody Risk risk) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                riskService.create(risk.title(), risk.description(), risk.probability(), risk.impact()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Risk> update(@PathVariable Long id, @RequestBody Risk risk) {
        return ResponseEntity.ok(riskService.update(id, risk));
    }
}
