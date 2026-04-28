package io.jenkins.plugins.pagerdutyv2;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;

import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Builds PagerDuty Events API v2 request bodies.
 */
public class PayloadBuilder {
    /**
     * Build a stable dedup_key as a SHA-256 hex digest of {@code JOB_NAME#BUILD_NUMBER}.
     * Stable for the lifetime of a single build; reproducible for tests.
     */
    public static @NonNull String dedupKey(@NonNull EnvVars env) {
        String job = env.get("JOB_NAME", "unknown-job");
        String build = env.get("BUILD_NUMBER", "0");
        String raw = job + "#" + build;

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return raw;
        }
    }

    public static @NonNull Map<String, Object> buildPayload(@NonNull EnvVars env, @NonNull String severity) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", "Jenkins " + env.get("JOB_NAME", "job") + " build " + env.get("BUILD_NUMBER", "?"));
        payload.put("source", env.get("JENKINS_URL", "jenkins"));
        payload.put("severity", severity);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("job_name", env.get("JOB_NAME"));
        details.put("build_number", env.get("BUILD_NUMBER"));
        details.put("build_url", env.get("BUILD_URL"));
        details.put("node_name", env.get("NODE_NAME"));
        String gitUrl = env.get("GIT_URL");
        if (gitUrl != null && !gitUrl.trim().isEmpty()) {
            details.put("git_url", gitUrl);
        }
        String gitBranch = env.get("GIT_BRANCH");
        if (gitBranch != null && !gitBranch.trim().isEmpty()) {
            details.put("git_branch", gitBranch);
        }
        payload.put("custom_details", details);
        return payload;
    }

    public static @NonNull Map<String, Object> buildBody(@NonNull String routingKey,
                                                         @NonNull String action,
                                                         @NonNull String dedupKey,
                                                         @NonNull Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("routing_key", routingKey);
        body.put("event_action", action);
        body.put("dedup_key", dedupKey);
        body.put("payload", payload);
        return body;
    }
}
