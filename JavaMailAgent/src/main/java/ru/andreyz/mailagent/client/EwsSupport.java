package ru.andreyz.mailagent.client;

import microsoft.exchange.webservices.data.core.ExchangeService;
import microsoft.exchange.webservices.data.core.enumeration.misc.ExchangeVersion;
import microsoft.exchange.webservices.data.credential.WebCredentials;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.MailAuthType;

import java.net.URI;

public final class EwsSupport {

    private EwsSupport() {
    }

    public static ExchangeService createService(MailConfig.MailProperties mailProperties,
                                                MailConfig.EwsProperties ewsProperties) {
        ExchangeService service = new ExchangeService(exchangeVersion(ewsProperties.getVersion()));
        service.setCredentials(webCredentials(mailProperties, ewsProperties));
        service.setTimeout(Math.max(1, ewsProperties.getTimeoutSeconds()) * 1000);
        service.setPreAuthenticate(MailAuthType.fromValue(ewsProperties.getAuthType(), MailAuthType.BASIC) == MailAuthType.BASIC);

        try {
            if (ewsProperties.isAutodiscover()) {
                service.autodiscoverUrl(mailProperties.getUsername(), url -> url != null && url.startsWith("https://"));
            } else if (ewsProperties.getUrl() != null && !ewsProperties.getUrl().isBlank()) {
                service.setUrl(URI.create(ewsProperties.getUrl()));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to configure EWS endpoint", e);
        }

        return service;
    }

    public static WebCredentials webCredentials(MailConfig.MailProperties mailProperties,
                                                MailConfig.EwsProperties ewsProperties) {
        String domain = ewsProperties.getDomain();
        if (domain != null && !domain.isBlank()) {
            return new WebCredentials(mailProperties.getUsername(), mailProperties.getPassword(), domain);
        }
        return new WebCredentials(mailProperties.getUsername(), mailProperties.getPassword());
    }

    public static ExchangeVersion exchangeVersion(String value) {
        if (value == null || value.isBlank()) {
            return ExchangeVersion.Exchange2010_SP2;
        }
        return ExchangeVersion.valueOf(value);
    }

    public static String connectionTarget(MailConfig.MailProperties mailProperties,
                                          MailConfig.EwsProperties ewsProperties) {
        if (ewsProperties.isAutodiscover()) {
            return "autodiscover:" + mailProperties.getUsername();
        }
        return ewsProperties.getUrl();
    }
}
