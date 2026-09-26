package com.qalab.qalabai.ai.gateway;

import com.qalab.qalabai.config.AiGatewayConfig;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression guard for outbound AI HTTP timeouts.
 *
 * <p>Before the fix every provider client built its own {@code new RestTemplate()},
 * whose {@code SimpleClientHttpRequestFactory} defaults to connect/read timeouts of
 * {@code 0} — i.e. <em>infinite</em>. A provider that accepted the connection and
 * then stalled would never raise, so {@link AiGateway#executeWithRetry} could
 * neither retry nor fail over, and the calling request thread hung forever.
 */
class AiHttpTimeoutTest {

    @Test
    void restTemplateBeanIsCreatedWithFiniteTimeouts() {
        RestTemplate template = new AiGatewayConfig().aiRestTemplate(10_000, 180_000);

        assertNotNull(template.getRequestFactory());
        var factory = template.getRequestFactory();
        assertTrue(factory.getClass().getName().contains("SimpleClientHttpRequestFactory"),
                "expected a request factory with settable timeouts, got " + factory.getClass());

        var requestFactory = (org.springframework.http.client.SimpleClientHttpRequestFactory) factory;
        // connectTimeout == 0 means infinite; assert the configured values landed.
        assertTrue(readInt(requestFactory, "connectTimeout") == 10_000,
                "connectTimeout should be 10000, was " + readInt(requestFactory, "connectTimeout"));
        assertTrue(readInt(requestFactory, "readTimeout") == 180_000,
                "readTimeout should be 180000, was " + readInt(requestFactory, "readTimeout"));
    }

    @Test
    void aStalledProviderRaisesInsteadOfHangingForever() throws Exception {
        // A server that accepts the connection and then never writes a response,
        // which is exactly the case that used to hang forever.
        try (ServerSocket server = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                try (Socket held = server.accept()) {
                    // hold the connection open, send nothing
                    Thread.sleep(5_000);
                } catch (IOException | InterruptedException ignored) {
                    // expected on close
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            RestTemplate template = new AiGatewayConfig().aiRestTemplate(1_000, 400);
            String url = "http://localhost:" + server.getLocalPort() + "/v1/chat/completions";

            long start = System.currentTimeMillis();
            assertThrows(Exception.class, () -> template.postForObject(url, "{}", String.class),
                    "a stalled provider must raise, not hang");
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(elapsed < 4_000,
                    "read timeout should fire near 400ms, took " + elapsed + "ms — infinite timeout still present");
        }
    }

    @Test
    void noProviderClientBuildsItsOwnRestTemplate() throws IOException {
        // Architectural guard: a bare `new RestTemplate()` re-introduces infinite
        // timeouts, so fail the build if one reappears in the AI packages.
        Path aiRoot = Path.of("src/main/java/com/qalab/qalabai/ai");
        assertTrue(Files.isDirectory(aiRoot), "expected sources at " + aiRoot.toAbsolutePath());

        try (Stream<Path> files = Files.walk(aiRoot)) {
            List<String> offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains("new RestTemplate()");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(Path::toString)
                    .toList();

            assertFalse(!offenders.isEmpty(),
                    "these files build their own RestTemplate (infinite timeouts): " + offenders);
        }
    }

    private static int readInt(Object factory, String field) {
        try {
            var f = factory.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.getInt(factory);
        } catch (ReflectiveOperationException e) {
            fail("could not read " + field + ": " + e);
            return -1;
        }
    }
}
