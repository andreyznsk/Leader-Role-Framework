package ru.andreyz.memoryservice.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.andreyz.memoryservice.dto.UsageStats;
import ru.andreyz.memoryservice.service.UsageStatsPeriod;
import ru.andreyz.memoryservice.service.UsageStatsService;

@RestController
@RequestMapping("/api/stats")
public class UsageStatsController {

    private final UsageStatsService usageStatsService;

    public UsageStatsController(UsageStatsService usageStatsService) {
        this.usageStatsService = usageStatsService;
    }

    @GetMapping("/usage")
    public ResponseEntity<UsageStats> usage(@RequestParam(defaultValue = "7d") String period) {
        return ResponseEntity.ok(usageStatsService.getStats(UsageStatsPeriod.from(period)));
    }
}
