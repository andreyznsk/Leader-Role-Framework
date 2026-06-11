package ru.andreyz.memoryservice.api;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.domain.DailyPlan;
import ru.andreyz.memoryservice.repository.DailyPlanRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

@RestController
@RequestMapping("/api/plans")
public class PlanController {

    private final DailyPlanRepository planRepository;

    public PlanController(DailyPlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    @GetMapping
    public ResponseEntity<DailyPlan> getPlan(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return planRepository.findByPlanDate(date)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<DailyPlan> createPlan(@RequestBody DailyPlan plan) {
        DailyPlan toSave = new DailyPlan(null, plan.planDate(), plan.summary(),
                "ACTIVE", Instant.now(), Instant.now(), Set.of());
        return ResponseEntity.ok(planRepository.save(toSave));
    }
}
