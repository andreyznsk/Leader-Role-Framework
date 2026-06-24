package ru.andreyz.mailagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@EnableConfigurationProperties({
    MailConfig.MailProperties.class,
    MailConfig.PathProperties.class,
    MailConfig.MemoryServiceProperties.class,
    MailConfig.MaildevProperties.class,
    MailConfig.ImapProperties.class,
    MailConfig.EwsProperties.class,
    MailConfig.TestConnectionProperties.class,
    MailConfig.AgentProperties.class,
    MailConfig.FolderProperties.class
})
public class MailConfig {

    @ConfigurationProperties(prefix = "mail")
    public static class MailProperties {
        private String protocol = "maildev";
        private String username;
        private String password;
        private int pollIntervalSeconds = 60;
        private int fetchLimit = 20;

        public String getProtocol() { return protocol; }
        public void setProtocol(String v) { this.protocol = v; }
        public String getUsername() { return username; }
        public void setUsername(String v) { this.username = v; }
        public String getPassword() { return password; }
        public void setPassword(String v) { this.password = v; }
        public int getPollIntervalSeconds() { return pollIntervalSeconds; }
        public void setPollIntervalSeconds(int v) { this.pollIntervalSeconds = v; }
        public int getFetchLimit() { return fetchLimit; }
        public void setFetchLimit(int v) { this.fetchLimit = v; }
    }

    @ConfigurationProperties(prefix = "path")
    public static class PathProperties {
        private String inbox = "inbox";
        private String processed = "processed";
        private String drafts = "drafts";
        private String plan = "plans/today.md";
        private String ragInbox = "rag-inbox";

        public String getInbox() { return inbox; }
        public void setInbox(String v) { this.inbox = v; }
        public String getProcessed() { return processed; }
        public void setProcessed(String v) { this.processed = v; }
        public String getDrafts() { return drafts; }
        public void setDrafts(String v) { this.drafts = v; }
        public String getPlan() { return plan; }
        public void setPlan(String v) { this.plan = v; }
        public String getRagInbox() { return ragInbox; }
        public void setRagInbox(String v) { this.ragInbox = v; }
    }

    @ConfigurationProperties(prefix = "memory.service")
    public static class MemoryServiceProperties {
        private String url = "http://localhost:8082";
        private boolean enabled = true;

        public String getUrl() { return url; }
        public void setUrl(String v) { this.url = v; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }

    @ConfigurationProperties(prefix = "maildev")
    public static class MaildevProperties {
        private String apiUrl = "http://localhost:1080";

        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String v) { this.apiUrl = v; }
    }

    @ConfigurationProperties(prefix = "imap")
    public static class ImapProperties {
        private String host;
        private int port = 993;
        private boolean ssl = true;
        private String folder = "INBOX";

        public String getHost() { return host; }
        public void setHost(String v) { this.host = v; }
        public int getPort() { return port; }
        public void setPort(int v) { this.port = v; }
        public boolean isSsl() { return ssl; }
        public void setSsl(boolean v) { this.ssl = v; }
        public String getFolder() { return folder; }
        public void setFolder(String v) { this.folder = v; }
    }

    @ConfigurationProperties(prefix = "ews")
    public static class EwsProperties {
        private String url;
        private boolean autodiscover = false;
        private String domain;
        private String authType = "NTLM";
        private String version = "Exchange2010_SP2";
        private int timeoutSeconds = 30;

        public String getUrl() { return url; }
        public void setUrl(String v) { this.url = v; }
        public boolean isAutodiscover() { return autodiscover; }
        public void setAutodiscover(boolean v) { this.autodiscover = v; }
        public String getDomain() { return domain; }
        public void setDomain(String v) { this.domain = v; }
        public String getAuthType() { return authType; }
        public void setAuthType(String v) { this.authType = v; }
        public String getVersion() { return version; }
        public void setVersion(String v) { this.version = v; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }
    }

    @ConfigurationProperties(prefix = "mail.test-connection")
    public static class TestConnectionProperties {
        private int timeoutSeconds = 15;
        private int maxFoldersToScan = 500;

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }
        public int getMaxFoldersToScan() { return maxFoldersToScan; }
        public void setMaxFoldersToScan(int v) { this.maxFoldersToScan = v; }
    }

    @ConfigurationProperties(prefix = "agent")
    public static class AgentProperties {
        private int timeoutMinutes = 5;

        public int getTimeoutMinutes() { return timeoutMinutes; }
        public void setTimeoutMinutes(int v) { this.timeoutMinutes = v; }
    }

    @ConfigurationProperties(prefix = "mail.folders")
    public static class FolderProperties {
        private List<String> exclude = List.of(
            "Sent", "Drafts", "Trash", "Spam", "Archive", "Junk", "Deleted Items"
        );

        public List<String> getExclude() { return exclude; }
        public void setExclude(List<String> v) { this.exclude = v; }
    }
}
