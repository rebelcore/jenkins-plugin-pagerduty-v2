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
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.Map;

/**
 * One-shot migration helper: extracts the {@code payload} sub-object from a
 * legacy full-body JSON string previously stored by {@link PagerDutyV2RunAction}.
 * Returns {@code "{}"} if extraction fails, or if the payload is not a JSON
 * object, so resolves degrade gracefully rather than throw.
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
            // Resolve reads the payload back as a JSON object, so anything else (a string or a
            // number in a damaged file) would make every resolve of this incident fail.
            if (!(payload instanceof Map)) {
                return "{}";
            }
            return MAPPER.writeValueAsString(payload);
        } catch (IOException | RuntimeException e) {
            return "{}";
        }
    }
}
