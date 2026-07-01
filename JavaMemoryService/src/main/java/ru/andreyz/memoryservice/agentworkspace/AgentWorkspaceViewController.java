package ru.andreyz.memoryservice.agentworkspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Controller
public class AgentWorkspaceViewController {

    @Value("${agent.provider:mock}")
    private String configuredProvider;

    @Value("${agentWorkspace.console.allowedCommands:claude}")
    private String allowedCommandsRaw;

    @GetMapping("/ui/agent-workspace")
    public String agentWorkspace(
            @RequestParam(required = false, defaultValue = "chat") String tab,
            Model model) {

        model.addAttribute("activeTab", tab);
        model.addAttribute("configuredProvider", configuredProvider);
        model.addAttribute("allowedCommands", List.of(allowedCommandsRaw.split(",\\s*")));

        return "agent-workspace";
    }
}
