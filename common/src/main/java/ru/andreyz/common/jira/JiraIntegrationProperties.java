package ru.andreyz.common.jira;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "jira")
public class JiraIntegrationProperties {

    private boolean enabled;
    private String baseUrl;
    private String username;
    private String token;
    private AuthType authType = AuthType.BEARER;
    private String defaultProject;
    private List<String> allowedProjects = new ArrayList<>();
    private String defaultIssueType = "Task";
    private boolean startupCheckEnabled = true;
    private final Timeout timeout = new Timeout();

    public enum AuthType {
        BEARER,
        BASIC
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public AuthType getAuthType() {
        return authType;
    }

    public void setAuthType(AuthType authType) {
        this.authType = authType;
    }

    public String getDefaultProject() {
        return defaultProject;
    }

    public void setDefaultProject(String defaultProject) {
        this.defaultProject = defaultProject;
    }

    public List<String> getAllowedProjects() {
        return allowedProjects;
    }

    public void setAllowedProjects(List<String> allowedProjects) {
        this.allowedProjects = allowedProjects;
    }

    public String getDefaultIssueType() {
        return defaultIssueType;
    }

    public void setDefaultIssueType(String defaultIssueType) {
        this.defaultIssueType = defaultIssueType;
    }

    public boolean isStartupCheckEnabled() {
        return startupCheckEnabled;
    }

    public void setStartupCheckEnabled(boolean startupCheckEnabled) {
        this.startupCheckEnabled = startupCheckEnabled;
    }

    public Timeout getTimeout() {
        return timeout;
    }

    public static class Timeout {
        private Duration connect = Duration.ofSeconds(5);
        private Duration read = Duration.ofSeconds(20);

        public Duration getConnect() {
            return connect;
        }

        public void setConnect(Duration connect) {
            this.connect = connect;
        }

        public Duration getRead() {
            return read;
        }

        public void setRead(Duration read) {
            this.read = read;
        }
    }
}
