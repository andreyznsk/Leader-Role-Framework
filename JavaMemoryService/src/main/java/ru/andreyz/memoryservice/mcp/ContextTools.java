package ru.andreyz.memoryservice.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import ru.andreyz.memoryservice.dto.ContextDto;
import ru.andreyz.memoryservice.service.ContextService;

@Component
public class ContextTools {

    private final ContextService contextService;

    public ContextTools(ContextService contextService) {
        this.contextService = contextService;
    }

    @Tool(description = "Get full session context: today plan, tomorrow plan, open incidents, open risks, recent people notes. Call at session start.")
    public ContextDto getContext() {
        return contextService.buildContext();
    }
}
