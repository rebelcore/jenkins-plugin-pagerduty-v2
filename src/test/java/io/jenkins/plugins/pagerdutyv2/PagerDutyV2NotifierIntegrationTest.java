package io.jenkins.plugins.pagerdutyv2;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.Builder;
import hudson.util.Secret;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@WithJenkins
public class PagerDutyV2NotifierIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();

    private void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/enqueue", new CaptureHandler(requestBodies));
        server.start();
    }

    private String endpointUrl() {
        int port = server.getAddress().getPort();
        return "http://127.0.0.1:" + port + "/v2/enqueue";
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void failureTriggersAndSuccessResolves(JenkinsRule j) throws Exception {
        startServer();

        // Configure global routing key + endpoint
        StringCredentialsImpl cred = new StringCredentialsImpl(
                CredentialsScope.GLOBAL,
                "pd-routing-key",
                "PagerDuty routing key",
                Secret.fromString("rk_test_123")
        );
        SystemCredentialsProvider.getInstance().getCredentials().add(cred);
        SystemCredentialsProvider.getInstance().save();

        PagerDutyV2GlobalConfiguration cfg = PagerDutyV2GlobalConfiguration.get();
        cfg.setDisabled(false);
        cfg.setEndpointUrl(endpointUrl());
        cfg.setRoutingKeyCredentialId("pd-routing-key");

        FreeStyleProject p = j.createFreeStyleProject();
        p.getBuildersList().add(new FailBuilder());

        PagerDutyV2Notifier n = new PagerDutyV2Notifier();
        n.setService("svc-a");
        n.setTags("team=oncall,env=test");
        n.setConsecutiveBuildsBeforeTrigger(1);
        n.setTriggerOnFailure(true);
        n.setResolveOnBackToNormal(true);
        p.getPublishersList().add(n);

        // Build #1 fails -> trigger
        FreeStyleBuild b1 = j.buildAndAssertStatus(Result.FAILURE, p);
        assertEquals(1, requestBodies.size(), "Should POST trigger once");

        Map<String, Object> trigger = MAPPER.readValue(requestBodies.get(0), Map.class);
        assertEquals("rk_test_123", trigger.get("routing_key"));
        assertEquals("trigger", trigger.get("event_action"));
        assertNotNull(trigger.get("dedup_key"));

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) trigger.get("payload");
        assertEquals("svc-a", payload.get("component"));

        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) payload.get("custom_details");
        assertEquals("svc-a", details.get("service"));
        assertEquals("FAILURE", String.valueOf(details.get("result")));
        assertEquals("team=oncall,env=test", details.get("tags"));
        assertFalse(details.containsKey("executor_disconnected"));
        assertFalse(details.containsKey("failure_reason"));

        PagerDutyV2RunAction a1 = b1.getAction(PagerDutyV2RunAction.class);
        assertNotNull(a1, "Trigger build should store PagerDutyV2RunAction");
        assertTrue(a1.isOpen(), "Incident should be open after trigger");
        assertFalse(a1.getPayloadJson().contains("rk_test_123"),
                "Persisted payload must not contain the routing key");
        assertFalse(a1.getPayloadJson().contains("routing_key"),
                "Persisted payload must not contain a routing_key field at all");

        // Build #2 succeeds -> resolve (remove failing builder)
        p.getBuildersList().clear();
        j.buildAndAssertSuccess(p);

        assertEquals(2, requestBodies.size(), "Should POST resolve after success");
        Map<String, Object> resolve = MAPPER.readValue(requestBodies.get(1), Map.class);
        assertEquals("resolve", resolve.get("event_action"));

        // Resolve should reuse the same dedup_key as the stored trigger JSON
        assertEquals(trigger.get("dedup_key"), resolve.get("dedup_key"));

        // The original action should be marked resolved
        assertFalse(a1.isOpen(), "Incident should be marked resolved after success");
    }

    @Test
    void listenerFiresWhenPublisherDidNotRun(JenkinsRule j) throws Exception {
        startServer();

        StringCredentialsImpl cred = new StringCredentialsImpl(
                CredentialsScope.GLOBAL,
                "pd-routing-key",
                "PagerDuty routing key",
                Secret.fromString("rk_test_123"));
        SystemCredentialsProvider.getInstance().getCredentials().add(cred);
        SystemCredentialsProvider.getInstance().save();

        PagerDutyV2GlobalConfiguration cfg = PagerDutyV2GlobalConfiguration.get();
        cfg.setDisabled(false);
        cfg.setEndpointUrl(endpointUrl());
        cfg.setRoutingKeyCredentialId("pd-routing-key");

        // Build a failing project with NO publisher attached: this is our
        // "agent disconnected, publisher never ran" simulation. Then attach
        // the publisher to the job afterwards so the listener can find it,
        // and manually fire the listener.
        FreeStyleProject p = j.createFreeStyleProject();
        p.getBuildersList().add(new FailBuilder());
        FreeStyleBuild b = j.buildAndAssertStatus(Result.FAILURE, p);
        assertEquals(0, requestBodies.size(), "no events while publisher absent");
        assertNull(b.getAction(PagerDutyV2HandledAction.class),
                "no HandledAction since publisher never ran");

        PagerDutyV2Notifier n = new PagerDutyV2Notifier();
        n.setService("svc-a");
        n.setTriggerOnFailure(true);
        p.getPublishersList().add(n);

        new PagerDutyV2RunListener().onFinalized(b);

        assertEquals(1, requestBodies.size(),
                "listener should dispatch trigger when publisher didn't run");
        Map<String, Object> trigger = MAPPER.readValue(requestBodies.get(0), Map.class);
        assertEquals("trigger", trigger.get("event_action"));
        assertEquals("rk_test_123", trigger.get("routing_key"));
        assertNotNull(b.getAction(PagerDutyV2HandledAction.class),
                "listener path must also stamp HandledAction so it doesn't double-fire");
    }

    @Test
    void listenerSkipsWhenPublisherAlreadyHandled(JenkinsRule j) throws Exception {
        startServer();

        StringCredentialsImpl cred = new StringCredentialsImpl(
                CredentialsScope.GLOBAL,
                "pd-routing-key",
                "PagerDuty routing key",
                Secret.fromString("rk_test_123"));
        SystemCredentialsProvider.getInstance().getCredentials().add(cred);
        SystemCredentialsProvider.getInstance().save();

        PagerDutyV2GlobalConfiguration cfg = PagerDutyV2GlobalConfiguration.get();
        cfg.setDisabled(false);
        cfg.setEndpointUrl(endpointUrl());
        cfg.setRoutingKeyCredentialId("pd-routing-key");

        FreeStyleProject p = j.createFreeStyleProject();
        p.getBuildersList().add(new FailBuilder());
        PagerDutyV2Notifier n = new PagerDutyV2Notifier();
        n.setService("svc-a");
        n.setTriggerOnFailure(true);
        p.getPublishersList().add(n);

        // Normal path: publisher runs, fires trigger, stamps HandledAction.
        FreeStyleBuild b = j.buildAndAssertStatus(Result.FAILURE, p);
        assertEquals(1, requestBodies.size());
        assertNotNull(b.getAction(PagerDutyV2HandledAction.class),
                "publisher should have stamped the marker");

        // Now manually invoke the listener — must be a no-op.
        new PagerDutyV2RunListener().onFinalized(b);
        assertEquals(1, requestBodies.size(),
                "listener must not double-fire when publisher already handled");
    }

    @Test
    void executorDisconnectFailureIsIncludedInPayload(JenkinsRule j) throws Exception {
        startServer();

        StringCredentialsImpl cred = new StringCredentialsImpl(
                CredentialsScope.GLOBAL,
                "pd-routing-key",
                "PagerDuty routing key",
                Secret.fromString("rk_test_123")
        );
        SystemCredentialsProvider.getInstance().getCredentials().add(cred);
        SystemCredentialsProvider.getInstance().save();

        PagerDutyV2GlobalConfiguration cfg = PagerDutyV2GlobalConfiguration.get();
        cfg.setDisabled(false);
        cfg.setEndpointUrl(endpointUrl());
        cfg.setRoutingKeyCredentialId("pd-routing-key");

        FreeStyleProject p = j.createFreeStyleProject();
        p.getBuildersList().add(new DisconnectLikeFailBuilder());

        PagerDutyV2Notifier n = new PagerDutyV2Notifier();
        n.setService("svc-a");
        n.setTriggerOnFailure(true);
        p.getPublishersList().add(n);

        j.buildAndAssertStatus(Result.FAILURE, p);
        assertEquals(1, requestBodies.size(), "Should POST trigger for disconnect failure");

        Map<String, Object> trigger = MAPPER.readValue(requestBodies.get(0), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) trigger.get("payload");
        assertTrue(String.valueOf(payload.get("summary")).contains("executor disconnected"));

        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) payload.get("custom_details");
        assertEquals(Boolean.TRUE, details.get("executor_disconnected"));
        assertEquals("executor_disconnected", details.get("failure_reason"));
    }

    @Test
    void consecutiveBuildsThresholdDelaysTrigger(JenkinsRule j) throws Exception {
        startServer();

        StringCredentialsImpl cred = new StringCredentialsImpl(
                CredentialsScope.GLOBAL,
                "pd-routing-key",
                "PagerDuty routing key",
                Secret.fromString("rk_test_123")
        );
        SystemCredentialsProvider.getInstance().getCredentials().add(cred);
        SystemCredentialsProvider.getInstance().save();

        PagerDutyV2GlobalConfiguration cfg = PagerDutyV2GlobalConfiguration.get();
        cfg.setDisabled(false);
        cfg.setEndpointUrl(endpointUrl());
        cfg.setRoutingKeyCredentialId("pd-routing-key");

        FreeStyleProject p = j.createFreeStyleProject();
        p.getBuildersList().add(new FailBuilder());

        PagerDutyV2Notifier n = new PagerDutyV2Notifier();
        n.setService("svc-a");
        n.setConsecutiveBuildsBeforeTrigger(2);
        n.setTriggerOnFailure(true);
        p.getPublishersList().add(n);

        // First failure: streak 1/2 -> no trigger
        j.buildAndAssertStatus(Result.FAILURE, p);
        assertEquals(0, requestBodies.size(), "Should not trigger until threshold reached");

        // Second consecutive failure: streak 2/2 -> trigger
        j.buildAndAssertStatus(Result.FAILURE, p);
        assertEquals(1, requestBodies.size(), "Should trigger on second consecutive failure");

        Map<String, Object> trigger = MAPPER.readValue(requestBodies.get(0), Map.class);
        assertEquals("trigger", trigger.get("event_action"));
    }

    /** Builder that fails the build (portable, no Shell step required). */
    public static class FailBuilder extends Builder {
        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
            listener.getLogger().println("FailBuilder: forcing FAILURE");
            return false;
        }
    }

    /** Builder that emits remoting disconnect markers before failing. */
    public static class DisconnectLikeFailBuilder extends Builder {
        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
            listener.getLogger().println("java.nio.channels.ClosedChannelException");
            listener.getLogger().println(
                    "java.io.IOException: Backing channel 'JNLP4-connect connection from "
                            + "10.248.88.35/10.248.88.35:34716' is disconnected.");
            return false;
        }
    }

    private static class CaptureHandler implements HttpHandler {
        private final List<String> out;

        private CaptureHandler(List<String> out) {
            this.out = out;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] body = readAll(exchange.getRequestBody());
            out.add(new String(body, StandardCharsets.UTF_8));

            byte[] resp = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        }

        private static byte[] readAll(InputStream in) throws IOException {
            byte[] buf = new byte[8192];
            int r;
            List<byte[]> chunks = new ArrayList<>();
            int total = 0;
            while ((r = in.read(buf)) != -1) {
                byte[] c = new byte[r];
                System.arraycopy(buf, 0, c, 0, r);
                chunks.add(c);
                total += r;
            }
            byte[] all = new byte[total];
            int off = 0;
            for (byte[] c : chunks) {
                System.arraycopy(c, 0, all, off, c.length);
                off += c.length;
            }
            return all;
        }
    }
}
