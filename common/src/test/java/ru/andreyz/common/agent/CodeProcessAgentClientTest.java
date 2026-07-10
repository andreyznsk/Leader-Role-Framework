package ru.andreyz.common.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeProcessAgentClientTest {

    @TempDir
    Path tempDir;

    @Test
    void keepsDirectCommandWithoutShellWrapper() {
        CodeProcessAgentClient client = new CodeProcessAgentClient("agent --print", 1);

        assertThat(client.buildProcessCommand(List.of("agent", "--print")))
                .containsExactly("agent", "--print");
    }

    @Test
    void wrapsCommandWithShellWhenEnabled() {
        CodeProcessAgentClient client = new CodeProcessAgentClient(
                "agent --print",
                1,
                true,
                "/bin/zsh",
                true,
                true,
                false,
                null,
                null);

        assertThat(client.buildProcessCommand(List.of("agent", "--print")))
                .containsExactly("/bin/zsh", "-lic", "agent --print");
    }

    @Test
    void completesPromptInDirectMode() throws Exception {
        Path script = createEchoScript();
        CodeProcessAgentClient client = new CodeProcessAgentClient(script.toString(), 1);

        String response = client.complete("probe");

        assertThat(response).isEqualTo("probe");
    }

    @Test
    void completesPromptInShellMode() throws Exception {
        Path script = createEchoScript();
        CodeProcessAgentClient client = new CodeProcessAgentClient(
                script.toString(),
                1,
                true,
                "/bin/sh",
                false,
                false,
                false,
                null,
                null);

        String response = client.complete("probe");

        assertThat(response).isEqualTo("probe");
    }

    @Test
    void resolvesAbsoluteExecutablePath() throws IOException {
        Path script = createEchoScript();
        CodeProcessAgentClient client = new CodeProcessAgentClient(script.toString(), 1);

        assertThat(client.resolveExecutable(script.toString()))
                .contains(script.toAbsolutePath().normalize().toString());
    }

    @Test
    void prependsPathForChildProcess() {
        CodeProcessAgentClient client = new CodeProcessAgentClient(
                "agent --print",
                1,
                false,
                null,
                false,
                false,
                false,
                null,
                "/custom/node/bin");
        ProcessBuilder pb = new ProcessBuilder("env");
        pb.environment().clear();
        pb.environment().putAll(new HashMap<>(System.getenv()));
        pb.environment().put("PATH", "/usr/bin");

        client.applyEnvironment(pb);

        assertThat(pb.environment().get("PATH")).startsWith("/custom/node/bin:");
    }

    private Path createEchoScript() throws IOException {
        Path script = tempDir.resolve("echo-script.sh");
        Files.writeString(script, "#!/bin/sh\ncat");
        script.toFile().setExecutable(true);
        return script;
    }
}
