package ru.andreyz.memoryservice.ui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.view.RedirectView;

@Controller
@RequestMapping("/ui/notice")
public class NoticeRedirectController {

    @GetMapping
    public RedirectView notice() {
        return new RedirectView("/ui/knowledge?type=RAG");
    }
}
