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

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Jazzer fuzz target for {@link LegacyBodyMigrator}. The legacy body comes from a build.xml written
 * by 1.0.0, so it can hold anything a damaged or hand-edited file holds. Whatever it is, migrating
 * it must not throw, which would stop the build loading, and must yield a payload that resolve can
 * read back as a JSON object.
 *
 * <p>The weekly fuzz workflow runs this with Jazzer. {@link FuzzInputsTest} replays every input
 * under {@code fuzz/LegacyBodyMigratorFuzzer} in each build.
 */
public final class LegacyBodyMigratorFuzzer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LegacyBodyMigratorFuzzer() {}

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        check(data.consumeRemainingAsBytes());
    }

    static void check(byte[] input) {
        String payloadJson = LegacyBodyMigrator.extractPayload(new String(input, StandardCharsets.UTF_8));
        try {
            if (!MAPPER.readTree(payloadJson).isObject()) {
                throw new IllegalStateException("payload is not a JSON object: " + payloadJson);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("payload is not JSON: " + payloadJson, e);
        }
    }
}
