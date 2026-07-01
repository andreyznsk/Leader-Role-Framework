package ru.andreyz.mailagent.client;

import microsoft.exchange.webservices.data.core.PropertySet;
import microsoft.exchange.webservices.data.core.enumeration.misc.ExchangeVersion;
import microsoft.exchange.webservices.data.core.enumeration.property.PropertyDefinitionFlags;
import microsoft.exchange.webservices.data.property.definition.PropertyDefinition;
import microsoft.exchange.webservices.data.property.definition.PropertyDefinitionBase;
import org.junit.jupiter.api.Test;
import ru.andreyz.mailagent.config.MailConfig;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class EwsMailClientTest {

    @Test
    void emailListPropertySetContainsOnlyFindItemSafeProperties() throws Exception {
        EwsMailClient client = new EwsMailClient(mailProperties(), ewsProperties());
        Method method = EwsMailClient.class.getDeclaredMethod("emailListPropertySet");
        method.setAccessible(true);

        PropertySet propertySet = (PropertySet) method.invoke(client);

        for (PropertyDefinitionBase propertyBase : propertySet) {
            PropertyDefinition property = (PropertyDefinition) propertyBase;
            assertThat(property.hasFlag(PropertyDefinitionFlags.CanFind, ExchangeVersion.Exchange2010_SP2))
                    .as("Property must be allowed in FindItem: %s", property)
                    .isTrue();
        }

        client.close();
    }

    private MailConfig.MailProperties mailProperties() {
        MailConfig.MailProperties properties = new MailConfig.MailProperties();
        properties.setUsername("user");
        properties.setPassword("pass");
        return properties;
    }

    private MailConfig.EwsProperties ewsProperties() {
        MailConfig.EwsProperties properties = new MailConfig.EwsProperties();
        properties.setUrl("https://mail.example.test/EWS/Exchange.asmx");
        properties.setVersion("Exchange2010_SP2");
        properties.setAuthType("BASIC");
        properties.setTimeoutSeconds(5);
        return properties;
    }
}
