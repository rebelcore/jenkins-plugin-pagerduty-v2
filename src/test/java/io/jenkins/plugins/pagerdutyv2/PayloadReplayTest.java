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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PayloadReplayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void resolveReusesPayload() throws Exception {
        Map<String,Object> payload = Map.of(
                "summary", "x",
                "source", "y",
                "severity", "critical",
                "custom_details", Map.of("a", 1, "b", "two")
        );

        Map<String,Object> trigger = PayloadBuilder.buildBody("rk", "trigger", "dk", payload);
        String json = MAPPER.writeValueAsString(trigger);

        Map<String,Object> restored = MAPPER.readValue(json, Map.class);
        restored.put("event_action", "resolve");

        assertEquals("dk", restored.get("dedup_key"));
        assertEquals("resolve", restored.get("event_action"));

        Object restoredPayload = restored.get("payload");
        assertNotNull(restoredPayload);
        assertEquals(MAPPER.readTree(MAPPER.writeValueAsString(payload)),
                     MAPPER.readTree(MAPPER.writeValueAsString(restoredPayload)));
    }
}
