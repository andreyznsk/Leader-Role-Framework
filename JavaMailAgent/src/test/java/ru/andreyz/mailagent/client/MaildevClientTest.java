package ru.andreyz.mailagent.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.Email;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MaildevClientTest {

    private MaildevClient newClient(String apiUrl) {
        MailConfig.MaildevProperties props = new MailConfig.MaildevProperties();
        props.setApiUrl(apiUrl);
        return new MaildevClient(new ObjectMapper(), props);
    }

    @Test
    void listUnreadThrowsMailExceptionWhenUnreachable() {
        MaildevClient client = newClient("http://localhost:19999");
        assertThrows(MailException.class, () -> client.listUnread(10));
    }

    @Test
    void markAsReadThrowsMailExceptionWhenUnreachable() {
        MaildevClient client = newClient("http://localhost:19999");
        assertThrows(MailException.class, () -> client.markAsRead("some-id"));
    }
}
