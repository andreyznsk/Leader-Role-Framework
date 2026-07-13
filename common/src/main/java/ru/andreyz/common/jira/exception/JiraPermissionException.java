package ru.andreyz.common.jira.exception;

public class JiraPermissionException extends JiraClientException {
    public JiraPermissionException(String message, Throwable cause) {
        super(message, cause);
    }
}
