package ru.andreyz.memoryservice.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.andreyz.memoryservice.dto.ControlPluginAuditDto;
import ru.andreyz.memoryservice.dto.ControlPluginDto;
import ru.andreyz.memoryservice.dto.ControlPluginSettingsResponse;
import ru.andreyz.memoryservice.dto.ControlPluginSettingsUpdateRequest;
import ru.andreyz.memoryservice.service.ControlPluginService;
import ru.andreyz.memoryservice.service.PluginSettingsService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/ui")
public class SettingsViewController {

    private static final Logger log = LoggerFactory.getLogger(SettingsViewController.class);

    private final PluginSettingsService pluginSettingsService;
    private final ControlPluginService controlPluginService;

    public SettingsViewController(PluginSettingsService pluginSettingsService,
                                  ControlPluginService controlPluginService) {
        this.pluginSettingsService = pluginSettingsService;
        this.controlPluginService = controlPluginService;
    }

    @GetMapping("/settings")
    public String settings(@RequestParam(defaultValue = "mail") String plugin,
                           @RequestParam(required = false) String saved,
                           @RequestParam(required = false) String failed,
                           @RequestParam(required = false) String message,
                           Model model) {
        model.addAttribute("plugin", plugin);
        model.addAttribute("system", pluginSettingsService.getSystemSettings());
        model.addAttribute("plugins", controlPluginService.listPlugins().stream()
                .map(this::toView)
                .toList());
        model.addAttribute("saved", saved);
        model.addAttribute("failed", failed);
        model.addAttribute("message", message);
        return "settings";
    }

    @PostMapping("/settings/plugins/{code}")
    public String savePluginSettings(@PathVariable String code,
                                     @RequestParam MultiValueMap<String, String> params,
                                     RedirectAttributes redirectAttributes) {
        try {
            controlPluginService.updatePluginSettings(code, new ControlPluginSettingsUpdateRequest(extractSettings(params)));
            redirectAttributes.addAttribute("plugin", code);
            redirectAttributes.addAttribute("saved", code);
        } catch (RuntimeException e) {
            log.error("", e);
            redirectAttributes.addAttribute("plugin", code);
            redirectAttributes.addAttribute("failed", code);
            redirectAttributes.addAttribute("message", e.getMessage());
        }
        return "redirect:/ui/settings";
    }

    private ControlPluginView toView(ControlPluginDto plugin) {
        try {
            return new ControlPluginView(
                    plugin,
                    controlPluginService.getPluginSettings(plugin.code()),
                    controlPluginService.getPluginAudit(plugin.code()),
                    null
            );
        } catch (RuntimeException e) {
            return new ControlPluginView(
                    plugin,
                    controlPluginService.getSnapshot(plugin.code()),
                    controlPluginService.getPluginAudit(plugin.code()),
                    e.getMessage()
            );
        }
    }

    private Map<String, String> extractSettings(MultiValueMap<String, String> params) {
        Map<String, String> settings = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            String rawKey = entry.getKey();
            if (!rawKey.startsWith("settings[") || !rawKey.endsWith("]")) {
                continue;
            }
            String key = rawKey.substring("settings[".length(), rawKey.length() - 1);
            List<String> values = entry.getValue();
            settings.put(key, values == null || values.isEmpty() ? "" : values.get(values.size() - 1));
        }
        return settings;
    }

    public record ControlPluginView(
            ControlPluginDto plugin,
            ControlPluginSettingsResponse descriptor,
            List<ControlPluginAuditDto> audit,
            String error
    ) {
    }
}
