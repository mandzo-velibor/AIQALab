package com.qalab.sdk;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QalabClientTest {

    private static HttpServer server;
    private static String url;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        HttpHandler handler = exchange -> {
            byte[] body = ("{\"status\":\"ok\",\"operationId\":\"op-test\",\"url\":\"" + exchange.getRequestURI() + "\"}")
                    .getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        };
        server.createContext("/api/v1", handler);
        server.start();
        url = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @Test
    void generatePostsAndReadsOperationId() {
        QalabClient client = new QalabClient(url).projectId("proj-1");
        ApiResult result = client.generate("https://example.com");
        assertTrue(result.isSuccess());
        assertEquals("op-test", result.operationId());
        assertTrue(result.body().contains("op-test"));
    }

    @Test
    void budgetPolicyEndpoints() {
        QalabClient client = new QalabClient(url);
        assertTrue(client.budgetPolicy().isSuccess());
        assertTrue(client.updateBudgetPolicy("SOFT").isSuccess());
    }

    @Test
    void intentDetects() {
        QalabClient client = new QalabClient(url).baseUrl("https://example.com");
        assertTrue(client.detectIntent("generate tests for the login page").isSuccess());
        assertTrue(client.intent("generate tests for the login page").isSuccess());
    }
}
