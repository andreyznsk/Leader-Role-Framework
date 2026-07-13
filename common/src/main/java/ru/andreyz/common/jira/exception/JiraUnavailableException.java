package ru.andreyz.common.jira.exception;

public class JiraUnavailableException extends JiraClientException {
    public JiraUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
