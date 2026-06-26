package ru.andreyz.mailagent.client;

import microsoft.exchange.webservices.data.core.ExchangeService;
import microsoft.exchange.webservices.data.core.enumeration.misc.ExchangeVersion;
import microsoft.exchange.webservices.data.core.exception.service.local.ServiceLocalException;
import microsoft.exchange.webservices.data.core.request.HttpClientWebRequest;
import microsoft.exchange.webservices.data.core.request.HttpWebRequest;
import microsoft.exchange.webservices.data.credential.WebCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.MailAuthType;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Utility for creating EWS {@link ExchangeService} instances.
 * <p>
 * Patches ews-java-api 2.0 NPE bug: {@code ServiceRequestBase.readResponse()} calls
 * {@code response.getResponseContentType().startsWith("text/xml")} but
 * {@code HttpClientWebRequest.getResponseContentType()} returns {@code null}
 * when the Exchange server omits the {@code Content-Type} header.
 * <p>
 * The fix intercepts the response via a custom {@link WrappedHttpClientWebRequest}
 * that supplies a fallback content type of {@code "text/xml; charset=utf-8"}
 * when the real value is {@code null}.
 * <p>
 * Also auto-fixes the EWS URL: if no {@code /EWS/Exchange.asmx} suffix is present
 * it is appended, matching the behaviour of Python's exchangelib.
 */
public final class EwsSupport {

    private static final String EWS_PATH = "/EWS/Exchange.asmx";

    private EwsSupport() {
    }

    private static final Logger log = LoggerFactory.getLogger(EwsSupport.class);

    public static ExchangeService createService(MailConfig.MailProperties mailProperties,
                                                MailConfig.EwsProperties ewsProperties) {
        ExchangeService service = new ExchangeService(exchangeVersion(ewsProperties.getVersion())) {
            @Override
            public HttpWebRequest prepareHttpWebRequest() throws ServiceLocalException, URISyntaxException {
                HttpWebRequest original = super.prepareHttpWebRequest();
                if (original instanceof HttpClientWebRequest) {
                    return new WrappedHttpClientWebRequest((HttpClientWebRequest) original);
                }
                return original;
            }
        };
        service.setCredentials(webCredentials(mailProperties, ewsProperties));
        service.setTimeout(Math.max(1, ewsProperties.getTimeoutSeconds()) * 1000);
        service.setPreAuthenticate(true);

        try {
            if (ewsProperties.isAutodiscover()) {
                log.debug("EWS autodiscover: {}", mailProperties.getUsername());
                service.autodiscoverUrl(mailProperties.getUsername(), url -> url != null && url.startsWith("https://"));
            } else if (ewsProperties.getUrl() != null && !ewsProperties.getUrl().isBlank()) {
                String ewsUrl = normalizeEwsUrl(ewsProperties.getUrl());
                log.debug("EWS manual URL: {} (normalized from {})", ewsUrl, ewsProperties.getUrl());
                service.setUrl(URI.create(ewsUrl));
            }
        } catch (Exception e) {
            log.error("Failed to configure EWS endpoint: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to configure EWS endpoint", e);
        }

        return service;
    }

    /**
     * Ensures the URL ends with {@code /EWS/Exchange.asmx}.
     * Python exchangelib does this automatically; ews-java-api requires the full path.
     */
    static String normalizeEwsUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        String normalized = url.trim();
        if (normalized.endsWith(EWS_PATH)) {
            return normalized;
        }
        if (normalized.endsWith("/")) {
            return normalized + EWS_PATH.substring(1);
        }
        return normalized + EWS_PATH;
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
