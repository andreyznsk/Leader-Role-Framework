package ru.andreyz.memoryservice.ui;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.andreyz.memoryservice.service.KnowledgeService;

import java.util.List;

@Controller
@RequestMapping("/ui/knowledge")
public class KnowledgeViewController {

    private static final List<String> DOCUMENT_TYPES = List.of("ALL", "NOTICE", "ADR", "PROCESS", "SERVICE_CARD");

    private final KnowledgeService knowledgeService;

    public KnowledgeViewController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping
    public String knowledge(@RequestParam(required = false) String type,
                            @RequestParam(required = false) Long id,
                            Model model) {
        String normalizedType = normalizeType(type);
        List<KnowledgeService.KnowledgeDocumentSummary> documents = knowledgeService.list(normalizedType);
        KnowledgeService.KnowledgeDocumentDetails selected = id != null ? knowledgeService.get(id) : null;
        model.addAttribute("documents", documents);
        model.addAttribute("selectedDocument", selected);
        model.addAttribute("selectedDocumentId", id);
        model.addAttribute("selectedType", normalizedType != null ? normalizedType : "ALL");
        model.addAttribute("documentTypes", DOCUMENT_TYPES);
        model.addAttribute("selectedTypeLabel", labelFor(normalizedType != null ? normalizedType : "ALL"));
        model.addAttribute("typeLabelResolver", this);
        model.addAttribute("pageTitle", "RAG Knowledge");
        return "knowledge";
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank() || "ALL".equalsIgnoreCase(type)) {
            return null;
        }
        return type.trim().toUpperCase();
    }

    public String labelFor(String type) {
        return switch (type) {
            case "NOTICE" -> "Notices";
            case "ADR" -> "ADR";
            case "PROCESS" -> "Processes";
            case "SERVICE_CARD" -> "Service Cards";
            default -> "All";
        };
    }
}
