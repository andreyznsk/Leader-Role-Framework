package ru.andreyz.memoryservice.ui;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.service.IncidentService;

@Controller
@RequestMapping("/ui/incidents")
public class IncidentViewController {

    private final IncidentService incidentService;

    public IncidentViewController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @GetMapping
    public String incidents(Model model) {
        model.addAttribute("incidents", incidentService.findAll());
        return "incidents";
    }

    @PostMapping("/add")
    public String add(@RequestParam String title,
                      @RequestParam String severity,
                      @RequestParam(required = false) String description) {
        incidentService.create(title, severity, description);
        return "redirect:/ui/incidents";
    }

    @PostMapping("/{id}/resolve")
    public String resolve(@PathVariable Long id,
                          @RequestParam String rootCause,
                          @RequestParam String actionItems) {
        incidentService.resolve(id, rootCause, actionItems);
        return "redirect:/ui/incidents";
    }
}
