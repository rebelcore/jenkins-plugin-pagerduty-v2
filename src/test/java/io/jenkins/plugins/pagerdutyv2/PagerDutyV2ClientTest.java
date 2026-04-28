package io.jenkins.plugins.pagerdutyv2;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PagerDutyV2ClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpServer startServer(IntFunction handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger count = new AtomicInteger();
        server.createContext("/v2/enqueue", (HttpExchange ex) -> {
            // drain body
            try (var in = ex.getRequestBody()) {
                in.readAllBytes();
            }
            int n = count.incrementAndGet();
            int code = handler.codeFor(n);
            byte[] resp = ("{\"attempt\":" + n + "}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, resp.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        return server;
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v2/enqueue";
    }

    private static OkHttpClient fastClient() {
        return new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(5))
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Test
    void retriesOn5xxThenSucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        startServer(n -> {
            attempts.set(n);
            return n < 3 ? 503 : 202;
        });

        new PagerDutyV2Client(url(), fastClient()).postEvent(Map.of("k", "v"));

        assertEquals(3, attempts.get(), "should have hit endpoint 3 times (2 failures + success)");
    }

    @Test
    void retriesOn429ThenSucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        startServer(n -> {
            attempts.set(n);
            return n < 2 ? 429 : 202;
        });

        new PagerDutyV2Client(url(), fastClient()).postEvent(Map.of("k", "v"));

        assertEquals(2, attempts.get());
    }

    @Test
    void doesNotRetryOn4xxOtherThan429() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        startServer(n -> {
            attempts.set(n);
            return 400;
        });

        IOException ex = assertThrows(IOException.class,
                () -> new PagerDutyV2Client(url(), fastClient()).postEvent(Map.of("k", "v")));
        assertTrue(ex.getMessage().contains("400"));
        assertEquals(1, attempts.get(), "4xx (non-429) should not be retried");
    }

    @Test
    void givesUpAfterMaxAttempts() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        startServer(n -> {
            attempts.set(n);
            return 500;
        });

        IOException ex = assertThrows(IOException.class,
                () -> new PagerDutyV2Client(url(), fastClient()).postEvent(Map.of("k", "v")));
        assertTrue(ex.getMessage().contains("500"));
        assertEquals(PagerDutyV2Client.MAX_ATTEMPTS, attempts.get());
    }

    @FunctionalInterface
    interface IntFunction {
        int codeFor(int attempt);
    }
}
