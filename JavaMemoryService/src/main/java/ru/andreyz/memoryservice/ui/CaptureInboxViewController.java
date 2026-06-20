package ru.andreyz.memoryservice.ui;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import ru.andreyz.memoryservice.service.CaptureService;

@Controller
@RequestMapping("/ui/captures")
public class CaptureInboxViewController {

    private final CaptureService captureService;

    public CaptureInboxViewController(CaptureService captureService) {
        this.captureService = captureService;
    }

    @GetMapping
    public String captures(Model model) {
        model.addAttribute("todayCaptures", captureService.findToday());
        model.addAttribute("recentCaptures", captureService.findRecent());
        return "captures";
    }
}
