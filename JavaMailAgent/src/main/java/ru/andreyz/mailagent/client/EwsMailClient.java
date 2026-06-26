package ru.andreyz.mailagent.client;

import microsoft.exchange.webservices.data.core.ExchangeService;
import microsoft.exchange.webservices.data.core.PropertySet;
import microsoft.exchange.webservices.data.core.enumeration.property.BasePropertySet;
import microsoft.exchange.webservices.data.core.enumeration.property.BodyType;
import microsoft.exchange.webservices.data.core.enumeration.property.WellKnownFolderName;
import microsoft.exchange.webservices.data.core.enumeration.search.FolderTraversal;
import microsoft.exchange.webservices.data.core.enumeration.service.ConflictResolutionMode;
import microsoft.exchange.webservices.data.core.service.folder.Folder;
import microsoft.exchange.webservices.data.core.service.item.EmailMessage;
import microsoft.exchange.webservices.data.core.service.item.Item;
import microsoft.exchange.webservices.data.core.service.schema.EmailMessageSchema;
import microsoft.exchange.webservices.data.core.service.schema.FolderSchema;
import microsoft.exchange.webservices.data.core.service.schema.ItemSchema;
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
import org.springframework.lang.Nullable;

public class EwsMailClient implements MailClient {

    private static final Logger log = LoggerFactory.getLogger(EwsMailClient.class);
    private static final String INBOX = "Inbox";

    private final MailConfig.MailProperties mailProperties;
    private final MailConfig.EwsProperties ewsProperties;
    private final ExchangeService service;
    private final List<String> excludeFolders;
    private final Map<String, FolderId> folderIdsByPath = new HashMap<>();
    private volatile boolean foldersInitialized;

    public EwsMailClient(MailConfig.MailProperties mailProperties,
                         MailConfig.EwsProperties ewsProperties) {
        this(mailProperties, ewsProperties, List.of());
    }

    public EwsMailClient(MailConfig.MailProperties mailProperties,
                         MailConfig.EwsProperties ewsProperties,
                         List<String> excludeFolders) {
        this.mailProperties = mailProperties;
        this.ewsProperties = ewsProperties;
        this.excludeFolders = excludeFolders != null ? excludeFolders : List.of();
        this.service = EwsSupport.createService(mailProperties, ewsProperties);
    }

    /**
     * Lazily initialises the folder-id map so that both {@link #listFolders}
     * and {@link #listUnread} work regardless of whether a new instance was
     * created by {@code RuntimeMailClient.delegateChecked()}.
     */
    private void ensureFoldersInitialized() throws MailException {
        if (foldersInitialized) {
            return;
        }
        synchronized (folderIdsByPath) {
            if (foldersInitialized) {
                return;
            }
            try {
                URI ewsUrl = service.getUrl();
                log.debug("EWS lazy init folders: connecting to {}, autodiscover={}, version={}",
                        ewsUrl, ewsProperties.isAutodiscover(), ewsProperties.getVersion());

                Folder inbox = Folder.bind(service, WellKnownFolderName.Inbox, folderPropertySet());
                folderIdsByPath.put(INBOX, inbox.getId());
                collectChildFolders(inbox, INBOX, excludeFolders, null);
                foldersInitialized = true;
                log.debug("EWS folder cache initialized with {} folders", folderIdsByPath.size());
            } catch (MailException e) {
                throw e;
            } catch (Exception e) {
                URI ewsUrl = service.getUrl();
                log.error("EWS lazy init failed at {}. Target: {}. Auth: {}. Error: {}",
                        ewsUrl, ewsProperties.getUrl(), ewsProperties.getAuthType(), e.getMessage(), e);
                throw new MailException("Failed to initialize EWS folders at %s: %s"
                        .formatted(ewsUrl != null ? ewsUrl : "unknown", e.getMessage()), e);
            }
        }
    }

    @Override
    public List<String> listFolders(List<String> excludeFolders) throws MailException {
        ensureFoldersInitialized();
        synchronized (folderIdsByPath) {
            List<String> folders = new ArrayList<>();
            for (String path : folderIdsByPath.keySet()) {
                String leaf = leafName(path);
                if (!isExcluded(path, excludeFolders) && !isExcluded(leaf, excludeFolders)) {
                    folders.add(path);
                }
            }
            log.debug("EWS folders selected for scan (from cache): {}", folders);
            return folders;
        }
    }

    @Override
    public List<Email> listUnread(String folder, int limit) throws MailException {
        ensureFoldersInitialized();
        FolderId folderId;
        synchronized (folderIdsByPath) {
            folderId = folderIdsByPath.get(folder);
        }
        if (folderId == null) {
            log.warn("EWS listUnread: folder '{}' not found in cache; keys={}", folder, folderIdsByPath.keySet());
            throw new MailException("Unknown EWS folder: " + folder);
        }

        try {
            ItemView view = new ItemView(limit);
            view.setPropertySet(emailListPropertySet());

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
        URI ewsUrl = service.getUrl();
        try {
            log.info("EWS testConnection: checking {}", ewsUrl);
            Folder inbox = Folder.bind(service, WellKnownFolderName.Inbox, folderPropertySet());
            log.info("EWS testConnection OK: {} unread={}", ewsUrl, inbox.getUnreadCount());
            return MailConnectionTestResult.connected(
                    "ews",
                    ewsProperties.getVersion(),
                    ewsProperties.getAuthType(),
                    null,
                    false,
                    inbox != null,
                    "Inbox unread: " + inbox.getUnreadCount(),
                    EwsSupport.connectionTarget(mailProperties, ewsProperties)
            );
        } catch (Exception e) {
            log.warn("EWS testConnection FAILED at {}: {}", ewsUrl, e.getMessage());
            return MailConnectionTestResult.failed(
                    "ews",
                    ewsProperties.getAuthType(),
                    null,
                    "Connection failed",
                    e.getMessage(),
                    EwsSupport.connectionTarget(mailProperties, ewsProperties)
            );
        }
    }

    @Override
    public void close() {
        service.close();
    }

    private void collectChildFolders(Folder parent,
                                     String parentPath,
                                     List<String> excludeFolders,
                                     @Nullable List<String> selectedFolders) throws Exception {
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

                if (selectedFolders != null && !isExcluded(path, excludeFolders)) {
                    selectedFolders.add(path);
                    collectChildFolders(child, path, excludeFolders, selectedFolders);
                } else if (selectedFolders == null) {
                    collectChildFolders(child, path, excludeFolders, null);
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
        String body = extractBody(message);
        LocalDateTime receivedAt = toLocalDateTime(message.getDateTimeReceived());
        return new Email(id, subject, from, defaultString(body, ""), receivedAt, folder);
    }

    /**
     * Safely extracts the body text. The body may not be loaded via FindItem,
     * so we load it lazily using a separate bind request if needed.
     */
    private String extractBody(EmailMessage message) throws Exception {
        try {
            microsoft.exchange.webservices.data.property.complex.MessageBody mb = message.getBody();
            if (mb != null) {
                return MessageBody.getStringFromMessageBody(mb);
            }
        } catch (Exception ignored) {
            // body not loaded via FindItem — fall through to lazy load
        }
        try {
            PropertySet bodyOnly = new PropertySet(ItemSchema.Body);
            bodyOnly.setRequestedBodyType(BodyType.Text);
            EmailMessage loaded = EmailMessage.bind(service, message.getId(), bodyOnly);
            return MessageBody.getStringFromMessageBody(loaded.getBody());
        } catch (Exception e) {
            log.debug("Could not load body for email {}: {}", message.getId(), e.getMessage());
            return "";
        }
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
                ItemSchema.DateTimeReceived,
                EmailMessageSchema.From,
                EmailMessageSchema.IsRead);
        return propertySet;
    }

}
