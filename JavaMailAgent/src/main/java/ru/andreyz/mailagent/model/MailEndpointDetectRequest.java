package ru.andreyz.mailagent.model;

public record MailEndpointDetectRequest(
        String protocol,
        String ewsUrl
) {
}
