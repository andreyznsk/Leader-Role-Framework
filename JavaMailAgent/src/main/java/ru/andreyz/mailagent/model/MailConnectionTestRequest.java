package ru.andreyz.mailagent.model;

import java.util.List;

public record MailConnectionTestRequest(
        String protocol,
        String ewsUrl,
        String username,
        String password,
        String authType,
        String host,
        Integer port,
        Boolean ssl,
        List<String> folderExclude
) {
}
