package ru.andreyz.memoryservice.ui;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/ui/captures")
public class CaptureInboxViewController {

    @GetMapping
    public String captures(@RequestParam(required = false, defaultValue = "") String status,
                           Model model) {
        model.addAttribute("activeStatus", status);
        return "captures";
    }
}
