package ru.andreyz.memoryservice.ui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PresentationController {

    @GetMapping("/ui/presentation")
    public String presentation() {
        return "presentation";
    }
}
