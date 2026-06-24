package ru.andreyz.mailagent.model;

import java.util.Locale;

public enum MailAuthType {
    BASIC,
    NTLM,
    OAUTH2;

    public static MailAuthType fromValue(String value, MailAuthType fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return MailAuthType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
