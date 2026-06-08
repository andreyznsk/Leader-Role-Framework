package ru.andreyz.mailagent.client;

import ru.andreyz.mailagent.model.Email;

import java.util.List;

public interface MailClient {
    List<Email> listUnread(int limit) throws MailException;
    void markAsRead(String emailId) throws MailException;
    void close();
}
