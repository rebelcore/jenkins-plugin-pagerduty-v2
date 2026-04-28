package io.jenkins.plugins.pagerdutyv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.io.IOException;
import java.util.Map;

/**
 * One-shot migration helper: extracts the {@code payload} sub-object from a
 * legacy full-body JSON string previously stored by {@link PagerDutyV2RunAction}.
 * Returns {@code "{}"} if extraction fails so resolves degrade gracefully rather
 * than throw at load time.
 */
final class LegacyBodyMigrator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LegacyBodyMigrator() {}

    static @NonNull String extractPayload(@CheckForNull String legacyBodyJson) {
        if (legacyBodyJson == null || legacyBodyJson.isBlank()) {
            return "{}";
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = MAPPER.readValue(legacyBodyJson, Map.class);
            Object payload = body.get("payload");
            if (payload == null) {
                return "{}";
            }
            return MAPPER.writeValueAsString(payload);
        } catch (IOException | RuntimeException e) {
            return "{}";
        }
    }
}
