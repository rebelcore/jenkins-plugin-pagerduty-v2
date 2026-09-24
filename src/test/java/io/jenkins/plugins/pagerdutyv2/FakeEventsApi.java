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

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hudson.util.Secret;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;

/**
 * An in-process stand-in for the PagerDuty Events API. It records every event posted to it and
 * answers with a status the test chooses, so tests can assert on exactly what the plugin sent
 * without a PagerDuty account.
 */
final class FakeEventsApi implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile int status = 202;

    FakeEventsApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/enqueue", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v2/enqueue";
    }

    /** Answers every following event with {@code status} instead of 202 Accepted. */
    void respondWith(int status) {
        this.status = status;
    }

    /** The events received so far, oldest first, as parsed JSON bodies. */
    List<Map<String, Object>> events() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String body : bodies) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> event = MAPPER.readValue(body, Map.class);
                out.add(event);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    /**
     * Points the plugin's global configuration at this server and stores {@code routingKey} as the
     * primary routing key credential.
     */
    void configurePlugin(String routingKey) throws IOException {
        addSecretText("pd-routing-key", routingKey);
        PagerDutyV2GlobalConfiguration cfg = PagerDutyV2GlobalConfiguration.get();
        cfg.setDisabled(false);
        cfg.setEndpointUrl(url());
        cfg.setRoutingKeyCredentialId("pd-routing-key");
    }

    static void addSecretText(String id, String secret) throws IOException {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(CredentialsScope.GLOBAL, id, id, Secret.fromString(secret)));
        SystemCredentialsProvider.getInstance().save();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] response = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }
}
