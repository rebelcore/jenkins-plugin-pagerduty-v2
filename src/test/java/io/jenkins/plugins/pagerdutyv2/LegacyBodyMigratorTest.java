package io.jenkins.plugins.pagerdutyv2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LegacyBodyMigratorTest {

    @Test
    void extractsPayloadAndStripsRoutingKey() {
        String legacy = "{\"routing_key\":\"rk_secret\",\"event_action\":\"trigger\","
                + "\"dedup_key\":\"abc\",\"payload\":{\"summary\":\"x\",\"severity\":\"error\"}}";

        String payloadJson = LegacyBodyMigrator.extractPayload(legacy);

        assertFalse(payloadJson.contains("rk_secret"), "routing key must not survive migration");
        assertFalse(payloadJson.contains("routing_key"), "routing_key field must not survive migration");
        assertTrue(payloadJson.contains("\"summary\":\"x\""));
        assertTrue(payloadJson.contains("\"severity\":\"error\""));
    }

    @Test
    void returnsEmptyObjectOnNullOrBlank() {
        assertEquals("{}", LegacyBodyMigrator.extractPayload(null));
        assertEquals("{}", LegacyBodyMigrator.extractPayload("   "));
    }

    @Test
    void returnsEmptyObjectOnMalformedJson() {
        assertEquals("{}", LegacyBodyMigrator.extractPayload("{not-json"));
    }

    @Test
    void returnsEmptyObjectWhenPayloadFieldMissing() {
        assertEquals("{}", LegacyBodyMigrator.extractPayload("{\"routing_key\":\"x\"}"));
    }
}
