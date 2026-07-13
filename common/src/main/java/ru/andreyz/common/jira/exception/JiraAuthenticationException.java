package ru.andreyz.common.jira.exception;

public class JiraAuthenticationException extends JiraClientException {
    public JiraAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
