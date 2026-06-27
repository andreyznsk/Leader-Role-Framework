package ru.andreyz.mailagent.model;

public enum MailConnectionErrorType {
    UNAUTHORIZED,
    TIMEOUT,
    SSL_ERROR,
    INVALID_ENDPOINT,
    NOT_SUPPORTED,
    ENDPOINT_UNREACHABLE,
    UNKNOWN
}
