package ru.andreyz.memoryservice.ui;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.andreyz.memoryservice.dto.UpdateMailPluginSettingsRequest;
import ru.andreyz.memoryservice.service.PluginSettingsService;

import java.util.Arrays;
import java.util.List;

@Controller
@RequestMapping("/ui")
public class SettingsViewController {

    private final PluginSettingsService pluginSettingsService;

    public SettingsViewController(PluginSettingsService pluginSettingsService) {
        this.pluginSettingsService = pluginSettingsService;
    }

    @GetMapping("/settings")
    public String settings(@RequestParam(defaultValue = "mail") String plugin,
                           @RequestParam(required = false) String saved,
                           @RequestParam(required = false) String testSuccess,
                           @RequestParam(required = false) String testMessage,
                           Model model) {
        var mailSettings = pluginSettingsService.getPluginSettings("mail");
        model.addAttribute("plugin", plugin);
        model.addAttribute("system", pluginSettingsService.getSystemSettings());
        model.addAttribute("plugins", pluginSettingsService.getPlugins());
        model.addAttribute("mail", mailSettings);
        model.addAttribute("mailIncludeText", String.join("\n", mailSettings.config().foldersInclude()));
        model.addAttribute("mailExcludeText", String.join("\n", mailSettings.config().foldersExclude()));
        model.addAttribute("saved", saved != null);
        model.addAttribute("testSuccess", testSuccess);
        model.addAttribute("testMessage", testMessage);
        return "settings";
    }

    @PostMapping("/settings/plugins/mail")
    public String saveMailSettings(@RequestParam(defaultValue = "false") boolean enabled,
                                   @RequestParam(defaultValue = "maildev") String protocol,
                                   @RequestParam(required = false) String login,
                                   @RequestParam(required = false) String password,
                                   @RequestParam(required = false) String secretRef,
                                   @RequestParam(required = false) String serverUrl,
                                   @RequestParam(required = false) String host,
                                   @RequestParam(required = false) Integer port,
                                   @RequestParam(defaultValue = "false") boolean ssl,
                                   @RequestParam(defaultValue = "60") Integer pollIntervalSeconds,
                                   @RequestParam(required = false) String foldersInclude,
                                   @RequestParam(required = false) String foldersExclude,
                                   @RequestParam(defaultValue = "false") boolean markNoiseAsRead,
                                   @RequestParam(defaultValue = "false") boolean moveProcessed,
                                   @RequestParam(required = false) String processedFolder,
                                   @RequestParam(required = false) String draftFolder,
                                   RedirectAttributes redirectAttributes) {
        pluginSettingsService.updateMailSettings(new UpdateMailPluginSettingsRequest(
                enabled,
                new UpdateMailPluginSettingsRequest.MailPluginConfigRequest(
                        protocol,
                        login,
                        password,
                        secretRef,
                        serverUrl,
                        host,
                        port,
                        ssl,
                        pollIntervalSeconds,
                        parseList(foldersInclude),
                        parseList(foldersExclude),
                        markNoiseAsRead,
                        moveProcessed,
                        processedFolder,
                        draftFolder
                )
        ));
        redirectAttributes.addAttribute("plugin", "mail");
        redirectAttributes.addAttribute("saved", "1");
        return "redirect:/ui/settings";
    }

    @PostMapping("/settings/plugins/mail/test-connection")
    public String testMailConnection(RedirectAttributes redirectAttributes) {
        var result = pluginSettingsService.testMailAgentConnection();
        redirectAttributes.addAttribute("plugin", "mail");
        redirectAttributes.addAttribute("testSuccess", result.success() ? "1" : "0");
        redirectAttributes.addAttribute("testMessage", result.message());
        return "redirect:/ui/settings";
    }

    private List<String> parseList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("[\\r\\n,]+"))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }
}
