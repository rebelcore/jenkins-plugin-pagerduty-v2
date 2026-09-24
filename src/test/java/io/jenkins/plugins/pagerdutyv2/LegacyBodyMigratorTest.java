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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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

    @Test
    void returnsEmptyObjectWhenPayloadIsNotAnObject() {
        // Found by LegacyBodyMigratorFuzzer: resolve reads the payload back as an object.
        assertEquals("{}", LegacyBodyMigrator.extractPayload("{\"payload\":\"a3f%1\"}"));
        assertEquals("{}", LegacyBodyMigrator.extractPayload("{\"payload\":7}"));
        assertEquals("{}", LegacyBodyMigrator.extractPayload("{\"payload\":[{\"summary\":\"x\"}]}"));
    }
}
