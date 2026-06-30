package ru.andreyz.mailagent.client;

import microsoft.exchange.webservices.data.core.ExchangeService;
import microsoft.exchange.webservices.data.core.PropertySet;
import microsoft.exchange.webservices.data.core.enumeration.property.BasePropertySet;
import microsoft.exchange.webservices.data.core.enumeration.property.BodyType;
import microsoft.exchange.webservices.data.core.enumeration.search.LogicalOperator;
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
import microsoft.exchange.webservices.data.property.complex.ConversationId;
import microsoft.exchange.webservices.data.search.FindFoldersResults;
import microsoft.exchange.webservices.data.search.FindItemsResults;
import microsoft.exchange.webservices.data.search.FolderView;
import microsoft.exchange.webservices.data.search.ItemView;
import microsoft.exchange.webservices.data.search.filter.SearchFilter;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.Email;
import ru.andreyz.mailagent.model.MailConnectionTestResult;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.lang.Nullable;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EwsMailClient implements MailClient {

    private static final String INBOX = "Inbox";
    private static final String AGGREGATED_PREFIX = "ews-conv:";

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

            List<EmailMessage> messages = new ArrayList<>();
            for (Item item : items.getItems()) {
                if (item instanceof EmailMessage message) {
                    messages.add(message);
                }
            }
            return aggregateByConversation(messages, folder);
        } catch (Exception e) {
            throw new MailException("Failed to list unread EWS emails from " + folder + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void markAsRead(String emailId, String folder) throws MailException {
        try {
            if (isAggregatedConversationId(emailId)) {
                markConversationAsRead(emailId, folder);
                return;
            }
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
            log.error("", e);
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
        List<String> recipients = recipients(message);
        String messageId = internetMessageId(message);
        String conversationId = conversationKey(message);
        String inReplyTo = inReplyTo(message);
        LocalDateTime receivedAt = toLocalDateTime(message.getDateTimeReceived());
        return new Email(id, subject, from, recipients, defaultString(body, ""), messageId, conversationId, inReplyTo, receivedAt, folder);
    }

    private List<Email> aggregateByConversation(List<EmailMessage> messages, String folder) throws Exception {
        Map<String, List<EmailMessage>> grouped = new LinkedHashMap<>();
        for (EmailMessage message : messages) {
            String key = conversationKey(message);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(message);
        }

        List<Email> result = new ArrayList<>();
        for (List<EmailMessage> conversationMessages : grouped.values()) {
            if (conversationMessages.size() == 1) {
                result.add(toEmail(conversationMessages.getFirst(), folder));
            } else {
                result.add(toConversationEmail(conversationMessages, folder));
            }
        }
        result.sort(Comparator.comparing(Email::receivedAt).reversed());
        return result;
    }

    private Email toConversationEmail(List<EmailMessage> messages, String folder) throws Exception {
        List<Email> parts = new ArrayList<>();
        for (EmailMessage message : messages) {
            parts.add(toEmail(message, folder));
        }
        parts.sort(Comparator.comparing(Email::receivedAt));

        Email newest = parts.getLast();
        String conversationId = conversationKey(messages.getFirst());
        String body = buildConversationBody(parts);
        String aggregateId = aggregatedConversationId(conversationId, newest.id());

        log.debug("EWS aggregated {} unread items into one conversation email: folder={}, conversationId={}, aggregateId={}",
                parts.size(), folder, conversationId, aggregateId);

        return new Email(
                aggregateId,
                newest.subject(),
                newest.from(),
                newest.recipients(),
                body,
                newest.messageId(),
                conversationId,
                newest.inReplyTo(),
                newest.receivedAt(),
                folder
        );
    }

    private String buildConversationBody(List<Email> parts) {
        StringBuilder body = new StringBuilder();
        for (int index = 0; index < parts.size(); index++) {
            Email part = parts.get(index);
            if (index > 0) {
                body.append("\n\n");
            }
            body.append("----- message ")
                    .append(index + 1)
                    .append(" / ")
                    .append(parts.size())
                    .append(" -----\n");
            body.append("From: ").append(defaultString(part.from(), "unknown")).append('\n');
            body.append("Subject: ").append(defaultString(part.subject(), "(no subject)")).append('\n');
            body.append("Received: ").append(part.receivedAt()).append("\n\n");
            body.append(defaultString(part.body(), ""));
        }
        return body.toString();
    }

    private String conversationKey(EmailMessage message) throws Exception {
        ConversationId conversationId = message.getConversationId();
        if (conversationId != null && conversationId.getUniqueId() != null && !conversationId.getUniqueId().isBlank()) {
            return conversationId.getUniqueId();
        }
        return message.getId().getUniqueId();
    }

    private boolean isAggregatedConversationId(String emailId) {
        return emailId != null && emailId.startsWith(AGGREGATED_PREFIX);
    }

    private String aggregatedConversationId(String conversationId, String newestItemId) {
        return AGGREGATED_PREFIX + encodeIdPart(conversationId) + ":" + encodeIdPart(newestItemId);
    }

    private void markConversationAsRead(String aggregateId, String folder) throws Exception {
        String conversationId = extractConversationId(aggregateId);
        FolderId folderId;
        synchronized (folderIdsByPath) {
            folderId = folderIdsByPath.get(folder);
        }
        if (folderId == null) {
            throw new MailException("Unknown EWS folder: " + folder);
        }

        SearchFilter unreadOnly = new SearchFilter.IsEqualTo(EmailMessageSchema.IsRead, false);
        SearchFilter sameConversation = new SearchFilter.IsEqualTo(ItemSchema.ConversationId, new ConversationId(conversationId));
        SearchFilter.SearchFilterCollection filter = new SearchFilter.SearchFilterCollection(
                LogicalOperator.And,
                unreadOnly,
                sameConversation
        );

        ItemView view = new ItemView(100);
        view.setPropertySet(new PropertySet(BasePropertySet.IdOnly, EmailMessageSchema.IsRead, ItemSchema.ConversationId));

        FindItemsResults<Item> items = service.findItems(folderId, filter, view);
        for (Item item : items.getItems()) {
            if (item instanceof EmailMessage message) {
                message.setIsRead(true);
                message.update(ConflictResolutionMode.AutoResolve);
            }
        }
    }

    private String extractConversationId(String aggregateId) {
        String payload = aggregateId.substring(AGGREGATED_PREFIX.length());
        int separator = payload.indexOf(':');
        if (separator < 0) {
            throw new IllegalArgumentException("Invalid aggregated EWS email id: " + aggregateId);
        }
        return decodeIdPart(payload.substring(0, separator));
    }

    private String encodeIdPart(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeIdPart(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
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
        } catch (Exception e) {
            log.error("", e);
            // body not loaded via FindItem — fall through to lazy load
        }
        try {
            PropertySet bodyOnly = new PropertySet(ItemSchema.Body);
            bodyOnly.setRequestedBodyType(BodyType.Text);
            EmailMessage loaded = EmailMessage.bind(service, message.getId(), bodyOnly);
            return MessageBody.getStringFromMessageBody(loaded.getBody());
        } catch (Exception e) {
            log.debug("Could not load body for email {}: {}", message.getId(), e.getMessage());
            log.error("", e);
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

    private List<String> recipients(EmailMessage message) {
        List<String> recipients = new ArrayList<>();
        recipients.addAll(addressesViaReflection(message, "getToRecipients"));
        recipients.addAll(addressesViaReflection(message, "getCcRecipients"));
        recipients.addAll(addressesViaReflection(message, "getBccRecipients"));
        return recipients.stream().distinct().toList();
    }

    private String internetMessageId(EmailMessage message) {
        return stringViaReflection(message, "getInternetMessageId");
    }

    private String inReplyTo(EmailMessage message) {
        return firstNonBlank(
                stringViaReflection(message, "getInReplyTo"),
                stringViaReflection(message, "getReferences")
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> addressesViaReflection(EmailMessage message, String methodName) {
        try {
            Object result = EmailMessage.class.getMethod(methodName).invoke(message);
            if (!(result instanceof Iterable<?> values)) {
                return List.of();
            }
            List<String> recipients = new ArrayList<>();
            for (Object value : values) {
                if (value instanceof EmailAddress address) {
                    String resolved = address.getAddress() != null && !address.getAddress().isBlank()
                            ? address.getAddress()
                            : address.getName();
                    if (resolved != null && !resolved.isBlank()) {
                        recipients.add(resolved);
                    }
                }
            }
            return recipients;
        } catch (Exception e) {
            log.error("", e);
            return List.of();
        }
    }

    private String stringViaReflection(EmailMessage message, String methodName) {
        try {
            Object result = EmailMessage.class.getMethod(methodName).invoke(message);
            if (result == null) {
                return null;
            }
            String value = result.toString();
            return value.isBlank() ? null : value;
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof microsoft.exchange.webservices.data.core.exception.service.local.ServiceObjectPropertyException) {
                log.debug("EWS property not loaded via findItems, skipping: method={}", methodName);
            } else {
                log.error("", e);
            }
            return null;
        } catch (Exception e) {
            log.error("", e);
            return null;
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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
                ItemSchema.ConversationId,
                EmailMessageSchema.From,
                EmailMessageSchema.IsRead,
                EmailMessageSchema.InternetMessageId,
                EmailMessageSchema.ToRecipients,
                EmailMessageSchema.CcRecipients,
                EmailMessageSchema.BccRecipients);
        return propertySet;
    }

}
