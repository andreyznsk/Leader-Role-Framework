package ru.andreyz.memoryservice.dto;

import java.util.List;

public record MailAgentConnectionTestRequestDto(
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
