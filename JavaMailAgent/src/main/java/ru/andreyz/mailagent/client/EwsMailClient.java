package ru.andreyz.mailagent.client;

import microsoft.exchange.webservices.data.core.ExchangeService;
import microsoft.exchange.webservices.data.core.PropertySet;
import microsoft.exchange.webservices.data.core.enumeration.misc.ExchangeVersion;
import microsoft.exchange.webservices.data.core.enumeration.property.BasePropertySet;
import microsoft.exchange.webservices.data.core.enumeration.property.BodyType;
import microsoft.exchange.webservices.data.core.enumeration.property.WellKnownFolderName;
import microsoft.exchange.webservices.data.core.enumeration.search.FolderTraversal;
import microsoft.exchange.webservices.data.core.enumeration.search.SortDirection;
import microsoft.exchange.webservices.data.core.enumeration.service.ConflictResolutionMode;
import microsoft.exchange.webservices.data.core.service.folder.Folder;
import microsoft.exchange.webservices.data.core.service.item.EmailMessage;
import microsoft.exchange.webservices.data.core.service.item.Item;
import microsoft.exchange.webservices.data.core.service.schema.EmailMessageSchema;
import microsoft.exchange.webservices.data.core.service.schema.FolderSchema;
import microsoft.exchange.webservices.data.core.service.schema.ItemSchema;
import microsoft.exchange.webservices.data.credential.WebCredentials;
import microsoft.exchange.webservices.data.property.complex.EmailAddress;
import microsoft.exchange.webservices.data.property.complex.FolderId;
import microsoft.exchange.webservices.data.property.complex.ItemId;
import microsoft.exchange.webservices.data.property.complex.MessageBody;
import microsoft.exchange.webservices.data.search.FindFoldersResults;
import microsoft.exchange.webservices.data.search.FindItemsResults;
import microsoft.exchange.webservices.data.search.FolderView;
import microsoft.exchange.webservices.data.search.ItemView;
import microsoft.exchange.webservices.data.search.filter.SearchFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.Email;
import ru.andreyz.mailagent.model.MailConnectionTestResult;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class EwsMailClient implements MailClient {

    private static final Logger log = LoggerFactory.getLogger(EwsMailClient.class);
    private static final String INBOX = "Inbox";

    private final MailConfig.MailProperties mailProperties;
    private final MailConfig.EwsProperties ewsProperties;
    private final ExchangeService service;
    private final Map<String, FolderId> folderIdsByPath = new HashMap<>();

    public EwsMailClient(MailConfig.MailProperties mailProperties,
                         MailConfig.EwsProperties ewsProperties) {
        this.mailProperties = mailProperties;
        this.ewsProperties = ewsProperties;
        this.service = createService(mailProperties, ewsProperties);
    }

    @Override
    public List<String> listFolders(List<String> excludeFolders) throws MailException {
        try {
            folderIdsByPath.clear();

            Folder inbox = Folder.bind(service, WellKnownFolderName.Inbox, folderPropertySet());
            folderIdsByPath.put(INBOX, inbox.getId());

            List<String> folders = new ArrayList<>();
            if (!isExcluded(INBOX, excludeFolders)) {
                folders.add(INBOX);
            }

            collectChildFolders(inbox, INBOX, excludeFolders, folders);
            log.debug("EWS folders selected for scan: {}", folders);
            return folders;
        } catch (Exception e) {
            throw new MailException("Failed to list EWS folders: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Email> listUnread(String folder, int limit) throws MailException {
        FolderId folderId = folderIdsByPath.get(folder);
        if (folderId == null) {
            throw new MailException("Unknown EWS folder: " + folder);
        }

        try {
            ItemView view = new ItemView(limit);
            view.setPropertySet(emailListPropertySet());
            view.getOrderBy().add(ItemSchema.DateTimeReceived, SortDirection.Ascending);

            SearchFilter unreadOnly = new SearchFilter.IsEqualTo(EmailMessageSchema.IsRead, false);
            FindItemsResults<Item> items = service.findItems(folderId, unreadOnly, view);

            List<Email> result = new ArrayList<>();
            for (Item item : items.getItems()) {
                if (item instanceof EmailMessage message) {
                    result.add(toEmail(message, folder));
                }
            }
            return result;
        } catch (Exception e) {
            throw new MailException("Failed to list unread EWS emails from " + folder + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void markAsRead(String emailId, String folder) throws MailException {
        try {
            EmailMessage message = EmailMessage.bind(service, new ItemId(emailId),
                    new PropertySet(EmailMessageSchema.IsRead));
            message.setIsRead(true);
            message.update(ConflictResolutionMode.AutoResolve);
        } catch (Exception e) {
            throw new MailException("Failed to mark EWS email " + emailId + " as read", e);
        }
    }

    @Override
    public MailConnectionTestResult testConnection() {
        try {
            Folder inbox = Folder.bind(service, WellKnownFolderName.Inbox, folderPropertySet());
            return new MailConnectionTestResult(
                    true,
                    "Inbox unread: " + inbox.getUnreadCount(),
                    connectionTarget()
            );
        } catch (Exception e) {
            return new MailConnectionTestResult(false, e.getMessage(), connectionTarget());
        }
    }

    @Override
    public void close() {
        service.close();
    }

    private void collectChildFolders(Folder parent,
                                     String parentPath,
                                     List<String> excludeFolders,
                                     List<String> selectedFolders) throws Exception {
        FolderView view = new FolderView(100);
        view.setTraversal(FolderTraversal.Shallow);
        view.setPropertySet(folderPropertySet());

        int offset = 0;
        boolean more;
        do {
            view.setOffset(offset);
            FindFoldersResults children = parent.findFolders(view);
            for (Folder child : children.getFolders()) {
                String path = parentPath + "/" + child.getDisplayName();
                folderIdsByPath.put(path, child.getId());

                if (!isExcluded(path, excludeFolders)) {
                    selectedFolders.add(path);
                    collectChildFolders(child, path, excludeFolders, selectedFolders);
                } else {
                    log.debug("EWS folder excluded from scan: {}", path);
                }
            }
            more = children.isMoreAvailable();
            offset = children.getNextPageOffset() != null ? children.getNextPageOffset() : 0;
        } while (more);
    }

    private boolean isExcluded(String folderPath, List<String> excludeFolders) {
        Set<String> normalized = normalizeExcludes(excludeFolders);
        String path = normalize(folderPath);
        String leaf = normalize(leafName(folderPath));
        return normalized.contains(path) || normalized.contains(leaf);
    }

    private Set<String> normalizeExcludes(List<String> excludeFolders) {
        Set<String> result = new HashSet<>();
        if (excludeFolders == null) {
            return result;
        }
        for (String raw : excludeFolders) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            for (String token : raw.split(",")) {
                if (!token.isBlank()) {
                    result.add(normalize(token));
                }
            }
        }
        return result;
    }

    private String leafName(String folderPath) {
        int slash = folderPath.lastIndexOf('/');
        return slash >= 0 ? folderPath.substring(slash + 1) : folderPath;
    }

    private String normalize(String value) {
        return value.trim()
                .replace('\\', '/')
                .replaceAll("/+", "/")
                .toLowerCase(Locale.ROOT);
    }

    private Email toEmail(EmailMessage message, String folder) throws Exception {
        String id = message.getId().getUniqueId();
        String subject = defaultString(message.getSubject(), "(no subject)");
        String from = fromAddress(message);
        String body = MessageBody.getStringFromMessageBody(message.getBody());
        LocalDateTime receivedAt = toLocalDateTime(message.getDateTimeReceived());
        return new Email(id, subject, from, defaultString(body, ""), receivedAt, folder);
    }

    private String fromAddress(EmailMessage message) throws Exception {
        EmailAddress from = message.getFrom();
        if (from == null) {
            return "unknown";
        }
        if (from.getAddress() != null && !from.getAddress().isBlank()) {
            return from.getAddress();
        }
        return defaultString(from.getName(), "unknown");
    }

    private LocalDateTime toLocalDateTime(Date date) {
        if (date == null) {
            return LocalDateTime.now();
        }
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }

    private String defaultString(String value, String fallback) {
        return value != null ? value : fallback;
    }

    private PropertySet folderPropertySet() {
        return new PropertySet(BasePropertySet.IdOnly,
                FolderSchema.DisplayName,
                FolderSchema.UnreadCount,
                FolderSchema.ChildFolderCount);
    }

    private PropertySet emailListPropertySet() {
        PropertySet propertySet = new PropertySet(BasePropertySet.IdOnly,
                ItemSchema.Subject,
                ItemSchema.Body,
                ItemSchema.DateTimeReceived,
                EmailMessageSchema.From,
                EmailMessageSchema.IsRead);
        propertySet.setRequestedBodyType(BodyType.Text);
        return propertySet;
    }

    private static ExchangeService createService(MailConfig.MailProperties mailProperties,
                                                 MailConfig.EwsProperties ewsProperties) {
        ExchangeService service = new ExchangeService(exchangeVersion(ewsProperties.getVersion()));
        service.setCredentials(webCredentials(mailProperties, ewsProperties));
        service.setTimeout(Math.max(1, ewsProperties.getTimeoutSeconds()) * 1000);
        service.setPreAuthenticate(true);

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

    private static WebCredentials webCredentials(MailConfig.MailProperties mailProperties,
                                                 MailConfig.EwsProperties ewsProperties) {
        String domain = ewsProperties.getDomain();
        if (domain != null && !domain.isBlank()) {
            return new WebCredentials(mailProperties.getUsername(), mailProperties.getPassword(), domain);
        }
        return new WebCredentials(mailProperties.getUsername(), mailProperties.getPassword());
    }

    private static ExchangeVersion exchangeVersion(String value) {
        if (value == null || value.isBlank()) {
            return ExchangeVersion.Exchange2010_SP2;
        }
        return ExchangeVersion.valueOf(value);
    }

    private String connectionTarget() {
        if (ewsProperties.isAutodiscover()) {
            return "autodiscover:" + mailProperties.getUsername();
        }
        return ewsProperties.getUrl();
    }
}
