package io.jenkins.plugins.pagerdutyv2;

import hudson.EnvVars;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PayloadBuilderTest {

    @Test
    void dedupKeyIsStableAndHexSha256() {
        EnvVars env = new EnvVars();
        env.put("JOB_NAME", "my-job");
        env.put("BUILD_NUMBER", "42");

        String k1 = PayloadBuilder.dedupKey(env);
        String k2 = PayloadBuilder.dedupKey(env);

        assertEquals(k1, k2, "dedupKey should be stable for the same env");
        assertEquals(64, k1.length(), "SHA-256 hex should be 64 chars");
        assertTrue(k1.matches("^[0-9a-f]{64}$"), "dedupKey should be lowercase hex");
    }

    @Test
    void buildPayloadIncludesGitFieldsOnlyWhenPresent() {
        EnvVars env = new EnvVars();
        env.put("JOB_NAME", "job");
        env.put("BUILD_NUMBER", "1");
        env.put("JENKINS_URL", "http://jenkins/");

        Map<String, Object> payload = PayloadBuilder.buildPayload(env, "critical");
        assertEquals("critical", payload.get("severity"));

        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) payload.get("custom_details");
        assertNotNull(details);
        assertFalse(details.containsKey("git_url"));
        assertFalse(details.containsKey("git_branch"));

        env.put("GIT_URL", "https://example/repo.git");
        env.put("GIT_BRANCH", "main");

        Map<String, Object> payload2 = PayloadBuilder.buildPayload(env, "error");
        @SuppressWarnings("unchecked")
        Map<String, Object> details2 = (Map<String, Object>) payload2.get("custom_details");
        assertEquals("https://example/repo.git", details2.get("git_url"));
        assertEquals("main", details2.get("git_branch"));
    }

    @Test
    void buildBodyHasExpectedShape() {
        Map<String, Object> payload = Map.of("summary", "x", "source", "y", "severity", "info");
        Map<String, Object> body = PayloadBuilder.buildBody("rk", "trigger", "dk", payload);

        assertEquals("rk", body.get("routing_key"));
        assertEquals("trigger", body.get("event_action"));
        assertEquals("dk", body.get("dedup_key"));
        assertSame(payload, body.get("payload"));
    }
}
