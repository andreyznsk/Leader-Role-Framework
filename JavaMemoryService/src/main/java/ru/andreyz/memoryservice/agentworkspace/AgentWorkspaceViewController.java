package ru.andreyz.memoryservice.agentworkspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.andreyz.memoryservice.search.*;

import java.util.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Controller
public class AgentWorkspaceViewController {


    private final GlobalSearchService searchService;

    @Value("${agent.provider:mock}")
    private String configuredProvider;

    @Value("${agentWorkspace.console.allowedCommands:claude}")
    private String allowedCommandsRaw;

    public AgentWorkspaceViewController(GlobalSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/ui/agent-workspace")
    public String agentWorkspace(
            @RequestParam(required = false, defaultValue = "chat") String tab,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "QUICK") String mode,
            @RequestParam(required = false) String[] layers,
            @RequestParam(required = false) String preset,
            Model model) {

        // if query present and no explicit tab — auto-switch to search
        String activeTab = (q != null && !q.isBlank() && "chat".equals(tab)) ? "search" : tab;

        model.addAttribute("activeTab", activeTab);
        model.addAttribute("configuredProvider", configuredProvider);
        model.addAttribute("allowedCommands", List.of(allowedCommandsRaw.split(",\\s*")));

        if ("search".equals(activeTab)) {
            List<LayerInfo> availableLayers = searchService.getLayers();
            model.addAttribute("availableLayers", availableLayers);
            model.addAttribute("query", q);
            model.addAttribute("searchMode", mode);
            model.addAttribute("preset", preset);

            List<String> selectedLayerNames = resolveSelectedLayers(layers, preset, availableLayers);
            model.addAttribute("selectedLayers", selectedLayerNames);

            if (q != null && !q.isBlank()) {
                List<SearchLayer> requestLayers = selectedLayerNames.stream()
                        .map(name -> {
                            try {
                                return SearchLayer.valueOf(name);
                            } catch (Exception e) {
                                log.error("", e);
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .toList();

                SearchMode searchMode;
                try {
                    searchMode = SearchMode.valueOf(mode.toUpperCase());
                } catch (Exception e) {
                    log.error("", e);
                    searchMode = SearchMode.QUICK;
                }

                SearchResponse response = searchService.search(new SearchRequest(q, requestLayers, searchMode, 20));
                model.addAttribute("searchResponse", response);

                Map<String, List<SearchResultItem>> grouped = new LinkedHashMap<>();
                for (SearchLayer layer : response.layers()) {
                    grouped.put(layer.name(), new ArrayList<>());
                }
                for (SearchResultItem item : response.results()) {
                    grouped.computeIfAbsent(item.layer().name(), k -> new ArrayList<>()).add(item);
                }
                model.addAttribute("resultsByLayer", grouped);
            }
        }

        return "agent-workspace";
    }

    private List<String> resolveSelectedLayers(String[] layers, String preset, List<LayerInfo> available) {
        if (preset != null) {
            return switch (preset) {
                case "notice" -> List.of("NOTICE");
                case "everything" -> available.stream().filter(LayerInfo::enabled).map(LayerInfo::name).toList();
                case "documentation" -> List.of("KNOWLEDGE");
                case "people_tasks" -> List.of("PEOPLE", "TASK");
                default -> enabledLayerNames(available);
            };
        }
        if (layers != null && layers.length > 0) return Arrays.asList(layers);
        return enabledLayerNames(available);
    }

    private List<String> enabledLayerNames(List<LayerInfo> available) {
        return available.stream().filter(LayerInfo::enabled).map(LayerInfo::name).toList();
    }
}
