package ru.andreyz.memoryservice.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ControlPluginStubServers {

    private static HttpServer mailServer;
    private static HttpServer ragServer;

    private static final AtomicReference<String> mailProtocol = new AtomicReference<>("maildev");
    private static final AtomicReference<String> mailEnabled = new AtomicReference<>("true");
    private static final AtomicReference<String> mailAuthType = new AtomicReference<>("NTLM");
    private static final AtomicReference<String> ragEnabled = new AtomicReference<>("true");
    private static final AtomicReference<String> ragScanInterval = new AtomicReference<>("60");
    private static final AtomicBoolean ragUnavailable = new AtomicBoolean(false);

    private ControlPluginStubServers() {
    }

    public static synchronized void ensureStarted() throws IOException {
        if (mailServer == null) {
            mailServer = HttpServer.create(new InetSocketAddress(19999), 0);
            mailServer.createContext("/actuator/health", exchange -> json(exchange, 200, """
                    {"status":"UP"}
                    """));
            mailServer.createContext("/api/control/settings", ControlPluginStubServers::handleMailSettings);
            mailServer.createContext("/api/control/audit", exchange -> json(exchange, 200, """
                    [
                      {
                        "appliedAt": "2026-06-22T20:30:00",
                        "status": "APPLIED",
                        "changedKeys": ["enabled", "protocol"],
                        "message": "Mail settings applied"
                      }
                    ]
                    """));
            mailServer.createContext("/api/control/plugin-state", exchange -> json(exchange, 200, "{\"accepted\":true}"));
            mailServer.createContext("/api/control/detect-endpoint", ControlPluginStubServers::handleMailDetectEndpoint);
            mailServer.createContext("/api/control/test-connection", ControlPluginStubServers::handleMailTestConnection);
            mailServer.setExecutor(Executors.newCachedThreadPool());
            mailServer.start();
        }

        if (ragServer == null) {
            ragServer = HttpServer.create(new InetSocketAddress(19998), 0);
            ragServer.createContext("/actuator/health", exchange -> {
                if (ragUnavailable.get()) {
                    json(exchange, 503, "{\"status\":\"DOWN\"}");
                    return;
                }
                json(exchange, 200, """
                        {"status":"UP"}
                        """);
            });
            ragServer.createContext("/api/control/settings", ControlPluginStubServers::handleRagSettings);
            ragServer.createContext("/api/control/audit", exchange -> json(exchange, 200, """
                    [
                      {
                        "appliedAt": "2026-06-22T21:00:00",
                        "status": "APPLIED",
                        "changedKeys": ["enabled", "scanIntervalSeconds"],
                        "message": "RAG settings applied"
                      }
                    ]
                    """));
            ragServer.setExecutor(Executors.newCachedThreadPool());
            ragServer.start();
        }
    }

    public static void reset() {
        mailProtocol.set("maildev");
        mailEnabled.set("true");
        mailAuthType.set("NTLM");
        ragEnabled.set("true");
        ragScanInterval.set("60");
        ragUnavailable.set(false);
    }

    public static void setRagUnavailable(boolean unavailable) {
        ragUnavailable.set(unavailable);
    }

    private static void handleMailSettings(HttpExchange exchange) throws IOException {
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            json(exchange, 200, """
                    {
                      "pluginCode": "mail",
                      "pluginName": "Mail Agent",
                      "version": 1,
                      "settings": {
                        "enabled": {
                          "value": "%s",
                          "type": "boolean",
                          "label": "Enabled",
                          "description": "Enable or disable mail polling",
                          "editable": true,
                          "secret": false,
                          "required": true
                        },
                        "protocol": {
                          "value": "%s",
                          "type": "select",
                          "label": "Protocol",
                          "description": "Mail protocol",
                          "editable": true,
                          "secret": false,
                          "required": true,
                          "options": ["maildev", "imap", "ews"]
                        },
                        "login": {
                          "value": "reader@example.com",
                          "type": "string",
                          "label": "Login",
                          "editable": true,
                          "secret": false,
                          "required": false
                        },
                        "password": {
                          "value": "*****",
                          "type": "secret",
                          "label": "Password / secret",
                          "editable": true,
                          "secret": true,
                          "required": false
                        },
                        "serverUrl": {
                          "value": "https://exchange.example.com/EWS/Exchange.asmx",
                          "type": "string",
                          "label": "Server URL",
                          "editable": true,
                          "secret": false,
                          "required": false
                        },
                        "authType": {
                          "value": "%s",
                          "type": "select",
                          "label": "Authentication Type",
                          "description": "BASIC and NTLM are supported. OAUTH2 is planned.",
                          "editable": true,
                          "secret": false,
                          "required": true,
                          "options": ["BASIC", "NTLM", "OAUTH2"]
                        },
                        "host": {
                          "value": "imap.example.com",
                          "type": "string",
                          "label": "Host",
                          "editable": true,
                          "secret": false,
                          "required": false
                        },
                        "port": {
                          "value": "993",
                          "type": "number",
                          "label": "Port",
                          "editable": true,
                          "secret": false,
                          "required": false
                        },
                        "ssl": {
                          "value": "true",
                          "type": "boolean",
                          "label": "Use SSL / TLS",
                          "editable": true,
                          "secret": false,
                          "required": false
                        },
                        "pollIntervalSeconds": {
                          "value": "30",
                          "type": "number",
                          "label": "Poll interval seconds",
                          "editable": true,
                          "secret": false,
                          "required": true
                        },
                        "foldersExclude": {
                          "value": "Inbox/CI/CD\\nJunk Email",
                          "type": "list",
                          "label": "Folders exclude",
                          "description": "One folder per line",
                          "editable": true,
                          "secret": false,
                          "required": false
                        }
                      }
                    }
                    """.formatted(mailEnabled.get(), mailProtocol.get(), mailAuthType.get()));
            return;
        }

        if ("PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (request.contains("\"enabled\":\"false\"")) {
                mailEnabled.set("false");
            }
            if (request.contains("\"enabled\":\"true\"")) {
                mailEnabled.set("true");
            }
            if (request.contains("\"protocol\":\"ews\"")) {
                mailProtocol.set("ews");
            }
            if (request.contains("\"protocol\":\"imap\"")) {
                mailProtocol.set("imap");
            }
            if (request.contains("\"authType\":\"NTLM\"")) {
                mailAuthType.set("NTLM");
            }
            if (request.contains("\"authType\":\"BASIC\"")) {
                mailAuthType.set("BASIC");
            }
            json(exchange, 200, """
                    {
                      "pluginCode": "mail",
                      "status": "APPLIED",
                      "appliedAt": "2026-06-22T20:30:00",
                      "applied": {
                        "enabled": "%s",
                        "protocol": "%s",
                        "authType": "%s"
                      },
                      "ignored": {},
                      "message": "Mail settings applied"
                    }
                    """.formatted(mailEnabled.get(), mailProtocol.get(), mailAuthType.get()));
            return;
        }

        json(exchange, 405, "{\"error\":\"Method not allowed\"}");
    }

    private static void handleRagSettings(HttpExchange exchange) throws IOException {
        if (ragUnavailable.get()) {
            json(exchange, 503, "{\"error\":\"RAG control API unavailable\"}");
            return;
        }
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            json(exchange, 200, """
                    {
                      "pluginCode": "rag",
                      "pluginName": "RAG Service",
                      "version": 3,
                      "settings": {
                        "enabled": {
                          "value": "%s",
                          "type": "boolean",
                          "label": "Enabled",
                          "description": "Enable or disable indexing",
                          "editable": true,
                          "secret": false,
                          "required": true
                        },
                        "scanIntervalSeconds": {
                          "value": "%s",
                          "type": "number",
                          "label": "Scan interval seconds",
                          "description": "Indexer loop interval",
                          "editable": true,
                          "secret": false,
                          "required": true
                        },
                        "ragInboxPath": {
                          "value": "rag-inbox",
                          "type": "string",
                          "label": "RAG inbox path",
                          "editable": true,
                          "secret": false,
                          "required": true
                        }
                      }
                    }
                    """.formatted(ragEnabled.get(), ragScanInterval.get()));
            return;
        }
        if ("PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (request.contains("\"enabled\":\"false\"")) {
                ragEnabled.set("false");
            }
            if (request.contains("\"enabled\":\"true\"")) {
                ragEnabled.set("true");
            }
            if (request.contains("\"scanIntervalSeconds\":\"120\"")) {
                ragScanInterval.set("120");
            }
            json(exchange, 200, """
                    {
                      "pluginCode": "rag",
                      "status": "APPLIED",
                      "appliedAt": "2026-06-22T21:00:00",
                      "applied": {
                        "enabled": "%s",
                        "scanIntervalSeconds": "%s"
                      },
                      "ignored": {},
                      "message": "RAG settings applied"
                    }
                    """.formatted(ragEnabled.get(), ragScanInterval.get()));
            return;
        }
        json(exchange, 405, "{\"error\":\"Method not allowed\"}");
    }

    private static void handleMailTestConnection(HttpExchange exchange) throws IOException {
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String authType = request.contains("\"authType\":\"NTLM\"") ? "NTLM" : mailAuthType.get();
        json(exchange, 200, """
                {
                  "success": true,
                  "status": "CONNECTED",
                  "protocol": "ews",
                  "endpointReachable": true,
                  "httpsOk": true,
                  "ewsDetected": true,
                  "authenticationOk": true,
                  "exchangeVersion": "Exchange2010_SP2",
                  "authType": "%s",
                  "mailbox": "reader@example.com",
                  "foldersFound": 127,
                  "foldersScanLimited": false,
                  "inboxFound": true,
                  "message": "Connected",
                  "errorType": null,
                  "details": null,
                  "endpoint": "https://exchange.example.com/EWS/Exchange.asmx",
                  "target": "https://exchange.example.com/EWS/Exchange.asmx"
                }
                """.formatted(authType));
    }

    private static void handleMailDetectEndpoint(HttpExchange exchange) throws IOException {
        json(exchange, 200, """
                {
                  "success": true,
                  "status": "DETECTED",
                  "protocol": "ews",
                  "endpointReachable": true,
                  "httpsOk": true,
                  "ewsDetected": true,
                  "httpStatus": 200,
                  "recommendedAuthType": "NTLM",
                  "message": "EWS endpoint detected",
                  "errorType": null,
                  "details": null,
                  "endpoint": "https://exchange.example.com/EWS/Exchange.asmx"
                }
                """);
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }
}
