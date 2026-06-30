package ru.andreyz.memoryservice.ui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

// /ui/search relocated to /ui/agent-workspace?tab=search (CR-MEM-012)
@Controller
public class SearchViewController {

    @GetMapping("/ui/search")
    public String redirect(@RequestParam(required = false) String q,
                           @RequestParam(required = false) String mode,
                           @RequestParam(required = false) String preset) {
        StringBuilder url = new StringBuilder("redirect:/ui/agent-workspace?tab=search");
        if (q != null && !q.isBlank()) url.append("&q=").append(q);
        if (mode != null) url.append("&mode=").append(mode);
        if (preset != null) url.append("&preset=").append(preset);
        return url.toString();
    }
}
