package ru.andreyz.mailagent.client;

import jakarta.mail.BodyPart;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.FlagTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.Email;
import ru.andreyz.mailagent.model.MailConnectionTestResult;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

public class ImapMailClient implements MailClient {

    private static final Logger log = LoggerFactory.getLogger(ImapMailClient.class);

    private final MailConfig.MailProperties mailProperties;
    private final MailConfig.ImapProperties imapProperties;
    private final Store store;

    public ImapMailClient(MailConfig.MailProperties mailProperties,
                          MailConfig.ImapProperties imapProperties) {
        this.mailProperties = mailProperties;
        this.imapProperties = imapProperties;
        this.store = connect(mailProperties, imapProperties);
    }

    @Override
    public List<String> listFolders(List<String> excludeFolders) throws MailException {
        try {
            Folder[] folders = store.getDefaultFolder().list("*");
            Set<String> excluded = normalizeExcludes(excludeFolders);
            List<String> result = new ArrayList<>();
            for (Folder folder : folders) {
                if ((folder.getType() & Folder.HOLDS_MESSAGES) != 0) {
                    String name = folder.getFullName();
                    if (!isExcluded(name, excluded)) {
                        result.add(name);
                    }
                }
            }
            log.debug("IMAP folders selected for scan: {}", result);
            return result;
        } catch (MessagingException e) {
            throw new MailException("Failed to list IMAP folders: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Email> listUnread(String folder, int limit) throws MailException {
        try {
            Folder f = store.getFolder(folder);
            f.open(Folder.READ_ONLY);
            try {
                Message[] messages = f.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
                FetchProfile profile = new FetchProfile();
                profile.add(FetchProfile.Item.ENVELOPE);
                profile.add(FetchProfile.Item.CONTENT_INFO);
                profile.add(UIDFolder.FetchProfileItem.UID);
                f.fetch(messages, profile);

                List<Email> result = new ArrayList<>();
                int count = Math.min(messages.length, limit);
                for (int i = 0; i < count; i++) {
                    result.add(toEmail(messages[i], folder, (UIDFolder) f));
                }
                return result;
            } finally {
                f.close(false);
            }
        } catch (MessagingException e) {
            throw new MailException("Failed to list unread IMAP emails from " + folder + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void markAsRead(String emailId, String folder) throws MailException {
        try {
            Folder f = store.getFolder(folder);
            f.open(Folder.READ_WRITE);
            try {
                long uid = Long.parseLong(emailId);
                Message message = ((UIDFolder) f).getMessageByUID(uid);
                if (message == null) {
                    log.warn("IMAP message uid={} not found in folder={}", uid, folder);
                    return;
                }
                message.setFlag(Flags.Flag.SEEN, true);
            } finally {
                f.close(false);
            }
        } catch (MessagingException e) {
            throw new MailException("Failed to mark IMAP email as read: " + emailId, e);
        }
    }

    @Override
    public MailConnectionTestResult testConnection() {
        try {
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            int unread = inbox.getUnreadMessageCount();
            inbox.close(false);
            return new MailConnectionTestResult(true, "INBOX unread: " + unread, connectionTarget());
        } catch (Exception e) {
            return new MailConnectionTestResult(false, e.getMessage(), connectionTarget());
        }
    }

    @Override
    public void close() {
        try {
            store.close();
        } catch (MessagingException e) {
            log.warn("Failed to close IMAP store", e);
        }
    }

    private Email toEmail(Message message, String folder, UIDFolder uidFolder) throws MessagingException {
        long uid = uidFolder.getUID(message);
        String subject = defaultString(message.getSubject(), "(no subject)");
        String from = fromAddress(message);
        String body = extractBody(message);
        LocalDateTime receivedAt = toLocalDateTime(message.getReceivedDate());
        return new Email(String.valueOf(uid), subject, from, body, receivedAt, folder);
    }

    private String fromAddress(Message message) throws MessagingException {
        jakarta.mail.Address[] from = message.getFrom();
        if (from == null || from.length == 0) {
            return "unknown";
        }
        jakarta.mail.Address addr = from[0];
        if (addr instanceof InternetAddress ia) {
            return ia.getAddress() != null ? ia.getAddress() : ia.getPersonal();
        }
        return addr.toString();
    }

    private String extractBody(Message message) {
        try {
            Object content = message.getContent();
            if (content instanceof String s) {
                return s;
            }
            if (content instanceof Multipart mp) {
                String htmlFallback = null;
                for (int i = 0; i < mp.getCount(); i++) {
                    BodyPart part = mp.getBodyPart(i);
                    if (part.isMimeType("text/plain")) {
                        return (String) part.getContent();
                    }
                    if (part.isMimeType("text/html") && htmlFallback == null) {
                        htmlFallback = (String) part.getContent();
                    }
                }
                return defaultString(htmlFallback, "");
            }
            return "";
        } catch (Exception e) {
            log.warn("Failed to extract email body", e);
            return "";
        }
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

    private boolean isExcluded(String folderName, Set<String> excluded) {
        String normalized = folderName.trim().toLowerCase(Locale.ROOT);
        String leaf = leafName(normalized);
        return excluded.contains(normalized) || excluded.contains(leaf);
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
                    result.add(token.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return result;
    }

    private String leafName(String folderName) {
        int sep = Math.max(folderName.lastIndexOf('/'), folderName.lastIndexOf('.'));
        return sep >= 0 ? folderName.substring(sep + 1) : folderName;
    }

    private String connectionTarget() {
        return (imapProperties.isSsl() ? "imaps" : "imap") + "://" +
                imapProperties.getHost() + ":" + imapProperties.getPort();
    }

    private static Store connect(MailConfig.MailProperties mailProperties,
                                 MailConfig.ImapProperties imapProperties) {
        String protocol = imapProperties.isSsl() ? "imaps" : "imap";
        Properties props = new Properties();
        props.put("mail.store.protocol", protocol);
        props.put("mail." + protocol + ".host", imapProperties.getHost());
        props.put("mail." + protocol + ".port", String.valueOf(imapProperties.getPort()));
        if (imapProperties.isSsl()) {
            props.put("mail.imaps.ssl.enable", "true");
        }

        Session session = Session.getInstance(props);
        try {
            Store store = session.getStore(protocol);
            store.connect(imapProperties.getHost(),
                    mailProperties.getUsername(),
                    mailProperties.getPassword());
            return store;
        } catch (MessagingException e) {
            throw new IllegalStateException("Failed to connect to IMAP server at " +
                    imapProperties.getHost() + ":" + imapProperties.getPort(), e);
        }
    }
}
