package ru.andreyz.memoryservice.ui;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.andreyz.memoryservice.service.UsageEventService;
import ru.andreyz.memoryservice.service.UsageStatsPeriod;
import ru.andreyz.memoryservice.service.UsageStatsService;

@Controller
@RequestMapping("/ui")
public class StatsViewController {

    private final UsageStatsService usageStatsService;
    private final UsageEventService usageEventService;

    public StatsViewController(UsageStatsService usageStatsService,
                               UsageEventService usageEventService) {
        this.usageStatsService = usageStatsService;
        this.usageEventService = usageEventService;
    }

    @GetMapping("/stats")
    public String stats(@RequestParam(defaultValue = "7d") String period, Model model) {
        UsageStatsPeriod statsPeriod = UsageStatsPeriod.from(period);
        model.addAttribute("period", statsPeriod.value());
        model.addAttribute("stats", usageStatsService.getStats(statsPeriod));
        model.addAttribute("events", usageEventService.getRecentEvents(statsPeriod, 50));
        model.addAttribute("periods", UsageStatsPeriod.values());
        return "stats";
    }
}
