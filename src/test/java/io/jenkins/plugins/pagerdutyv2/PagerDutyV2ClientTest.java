// Copyright 2010 Rebel Media
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.jenkins.plugins.pagerdutyv2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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

    /** Answers every request with {@code code} and {@code body}. */
    private void startServerAnswering(int code, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/enqueue", (HttpExchange ex) -> {
            try (var in = ex.getRequestBody()) {
                in.readAllBytes();
            }
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, resp.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
    }

    /**
     * Closes the connection without an answer for the first {@code drops} requests, as a proxy or
     * load balancer that fails mid-request does, and accepts the ones after. Returns the request count.
     */
    private AtomicInteger startServerDroppingFirst(int drops) throws IOException {
        AtomicInteger count = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/enqueue", (HttpExchange ex) -> {
            try (var in = ex.getRequestBody()) {
                in.readAllBytes();
            }
            if (count.incrementAndGet() > drops) {
                ex.sendResponseHeaders(202, -1);
            }
            ex.close();
        });
        server.start();
        return count;
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

    /** OkHttp would otherwise repeat a request on a dropped connection itself, hiding it from the plugin. */
    private static OkHttpClient clientLeavingRetriesToThePlugin() {
        return fastClient().newBuilder().retryOnConnectionFailure(false).build();
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

        IOException ex = assertThrows(
                IOException.class, () -> new PagerDutyV2Client(url(), fastClient()).postEvent(Map.of("k", "v")));
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

        IOException ex = assertThrows(
                IOException.class, () -> new PagerDutyV2Client(url(), fastClient()).postEvent(Map.of("k", "v")));
        assertTrue(ex.getMessage().contains("500"));
        assertEquals(PagerDutyV2Client.MAX_ATTEMPTS, attempts.get());
    }

    @Test
    void retriesWhenTheConnectionDrops() throws Exception {
        AtomicInteger attempts = startServerDroppingFirst(1);

        new PagerDutyV2Client(url(), clientLeavingRetriesToThePlugin()).postEvent(Map.of("k", "v"));

        assertEquals(2, attempts.get(), "one dropped connection, then the event is delivered");
    }

    @Test
    void givesUpWhenTheConnectionKeepsDropping() throws Exception {
        AtomicInteger attempts = startServerDroppingFirst(Integer.MAX_VALUE);

        assertThrows(
                IOException.class,
                () -> new PagerDutyV2Client(url(), clientLeavingRetriesToThePlugin()).postEvent(Map.of("k", "v")));
        assertEquals(PagerDutyV2Client.MAX_ATTEMPTS, attempts.get());
    }

    @Test
    void aLongErrorResponseIsShortenedInTheMessage() throws Exception {
        startServerAnswering(400, "x".repeat(1000));

        IOException ex = assertThrows(
                IOException.class, () -> new PagerDutyV2Client(url(), fastClient()).postEvent(Map.of("k", "v")));

        assertTrue(ex.getMessage().endsWith(" " + "x".repeat(500) + "..."), ex.getMessage());
    }

    @FunctionalInterface
    interface IntFunction {
        int codeFor(int attempt);
    }
}
