package ru.andreyz.mailagent.client;

import ru.andreyz.mailagent.model.Email;

import java.util.List;

public interface MailClient {
    List<String> listFolders(List<String> excludeFolders) throws MailException;
    List<Email> listUnread(String folder, int limit) throws MailException;
    void markAsRead(String emailId, String folder) throws MailException;
    void close();
}
