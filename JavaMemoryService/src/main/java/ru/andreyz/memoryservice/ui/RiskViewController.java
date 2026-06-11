package ru.andreyz.memoryservice.ui;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.domain.Risk;
import ru.andreyz.memoryservice.service.RiskService;

import java.time.Instant;

@Controller
@RequestMapping("/ui/risks")
public class RiskViewController {

    private final RiskService riskService;

    public RiskViewController(RiskService riskService) {
        this.riskService = riskService;
    }

    @GetMapping
    public String risks(@RequestParam(required = false) String status, Model model) {
        model.addAttribute("risks", status != null
                ? riskService.findByStatus(status)
                : riskService.findAll());
        model.addAttribute("statusFilter", status);
        return "risks";
    }

    @PostMapping("/add")
    public String add(@RequestParam String title,
                      @RequestParam(required = false) String description,
                      @RequestParam(defaultValue = "MEDIUM") String probability,
                      @RequestParam(defaultValue = "MEDIUM") String impact) {
        riskService.create(title, description, probability, impact);
        return "redirect:/ui/risks";
    }

    @PostMapping("/{id}/edit")
    public String edit(@PathVariable Long id,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String mitigation) {
        Risk existing = riskService.findById(id);
        Risk updated = new Risk(existing.id(), existing.title(), existing.description(),
                existing.probability(), existing.impact(),
                status != null ? status : existing.status(),
                mitigation != null ? mitigation : existing.mitigation(),
                existing.createdAt(), Instant.now());
        riskService.update(id, updated);
        return "redirect:/ui/risks";
    }
}
