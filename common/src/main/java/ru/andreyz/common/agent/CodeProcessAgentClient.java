package ru.andreyz.common.agent;

import jakarta.annotation.PostConstruct;
import org.springframework.util.StringUtils;

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CodeProcessAgentClient implements AgentClient {


    private final List<String> command;
    private final int timeoutMinutes;
    private final boolean shellEnabled;
    private final String shellExecutable;
    private final boolean shellLogin;
    private final boolean shellInteractive;
    private final boolean startupProbeEnabled;
    private final String startupProbePrompt;
    private final String pathPrepend;

    public CodeProcessAgentClient(String command, int timeoutMinutes) {
        this(command, timeoutMinutes, false, null, false, false, false, null, null);
    }

    public CodeProcessAgentClient(String command,
                                  int timeoutMinutes,
                                  boolean shellEnabled,
                                  String shellExecutable,
                                  boolean shellLogin,
                                  boolean shellInteractive,
                                  boolean startupProbeEnabled,
                                  String startupProbePrompt,
                                  String pathPrepend) {
        String[] tokens = StringUtils.tokenizeToStringArray(command, " ");
        if (tokens == null || tokens.length == 0) {
            throw new IllegalArgumentException("agent.command must not be blank");
        }
        this.command = List.copyOf(Arrays.asList(tokens));
        this.timeoutMinutes = timeoutMinutes;
        this.shellEnabled = shellEnabled;
        this.shellExecutable = StringUtils.hasText(shellExecutable) ? shellExecutable : detectShellExecutable();
        this.shellLogin = shellLogin;
        this.shellInteractive = shellInteractive;
        this.startupProbeEnabled = startupProbeEnabled;
        this.startupProbePrompt = StringUtils.hasText(startupProbePrompt) ? startupProbePrompt : "ping";
        this.pathPrepend = StringUtils.hasText(pathPrepend) ? pathPrepend : "";
    }

    @PostConstruct
    public void init() {
        log.info("AgentClient command: {} (timeout={}m, mode={})",
                String.join(" ", command),
                timeoutMinutes,
                shellEnabled ? "shell" : "direct");

        log.info("AgentClient runtime: cwd={}, userHome={}, os={}, shellEnv={}",
                System.getProperty("user.dir"),
                System.getProperty("user.home"),
                System.getProperty("os.name"),
                valueOrPlaceholder(System.getenv("SHELL")));
        log.info("AgentClient PATH: {}", valueOrPlaceholder(System.getenv("PATH")));
        if (StringUtils.hasText(pathPrepend)) {
            log.info("AgentClient PATH prepend: {}", pathPrepend);
        }

        if (shellEnabled) {
            log.info("AgentClient shell: executable={}, login={}, interactive={}, rcFiles={}",
                    shellExecutable,
                    shellLogin,
                    shellInteractive,
                    describeRcFiles());
        } else {
            String executable = command.getFirst();
            log.info("AgentClient executable lookup: requested={}, resolved={}",
                    executable,
                    resolveExecutable(executable).orElse("NOT FOUND IN PATH"));
        }

        if (startupProbeEnabled) {
            try {
                String response = complete(startupProbePrompt);
                log.info("AgentClient startup probe OK: promptChars={}, responseChars={}",
                        startupProbePrompt.length(),
                        response.length());
            } catch (Exception e) {
                log.error("AgentClient startup probe FAILED: {}", e.getMessage(), e);
            }
        }
    }

    @Override
    public String complete(String prompt) throws AgentException {
        try {
            return run(prompt, command, String.join(" ", command));
        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentException(String.join(" ", command) + " failed: " + e.getMessage(), e);
        }
    }

    private String run(String prompt, List<String> command, String commandLabel) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(buildProcessCommand(command));
        applyEnvironment(pb);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        try (var writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(prompt);
        }

        boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new AgentException(commandLabel + " timed out after " + timeoutMinutes + "m");
        }

        if (process.exitValue() != 0) {
            String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new AgentException(commandLabel + " exited with code " + process.exitValue() + ": " + error);
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        log.debug("Agent raw output ({} chars)", output.length());
        return output;
    }

    void applyEnvironment(ProcessBuilder pb) {
        if (!StringUtils.hasText(pathPrepend)) {
            return;
        }

        String currentPath = pb.environment().getOrDefault("PATH", "");
        if (StringUtils.hasText(currentPath)) {
            pb.environment().put("PATH", pathPrepend + File.pathSeparator + currentPath);
            return;
        }
        pb.environment().put("PATH", pathPrepend);
    }

    List<String> buildProcessCommand(List<String> rawCommand) {
        if (!shellEnabled) {
            return rawCommand;
        }

        List<String> shellCommand = new ArrayList<>();
        shellCommand.add(shellExecutable);
        shellCommand.add(shellModeFlag());
        shellCommand.add(String.join(" ", rawCommand));
        return List.copyOf(shellCommand);
    }

    String shellModeFlag() {
        if (shellLogin && shellInteractive) {
            return "-lic";
        }
        if (shellLogin) {
            return "-lc";
        }
        if (shellInteractive) {
            return "-ic";
        }
        return "-c";
    }

    String detectShellExecutable() {
        return Optional.ofNullable(System.getenv("SHELL"))
                .filter(StringUtils::hasText)
                .orElse("/bin/sh");
    }

    Optional<String> resolveExecutable(String executable) {
        if (executable.contains("/")) {
            Path path = Path.of(executable);
            return isExecutable(path) ? Optional.of(path.toAbsolutePath().normalize().toString()) : Optional.empty();
        }

        String pathEnv = System.getenv("PATH");
        if (!StringUtils.hasText(pathEnv)) {
            return Optional.empty();
        }

        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (!StringUtils.hasText(dir)) {
                continue;
            }
            Path candidate = Path.of(dir, executable);
            if (isExecutable(candidate)) {
                return Optional.of(candidate.toAbsolutePath().normalize().toString());
            }
        }
        return Optional.empty();
    }

    private boolean isExecutable(Path path) {
        return Files.isRegularFile(path) && Files.isExecutable(path);
    }

    private String describeRcFiles() {
        Path home = Path.of(System.getProperty("user.home"));
        return Stream.of(".zshenv", ".zprofile", ".zshrc", ".bash_profile", ".bashrc", ".profile")
                .map(name -> formatRcFile(home.resolve(name)))
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
    }

    private String formatRcFile(Path path) {
        return path.getFileName() + "=" + (Files.exists(path) ? "present" : "missing");
    }

    private String valueOrPlaceholder(String value) {
        return StringUtils.hasText(value) ? value : "<empty>";
    }
}
