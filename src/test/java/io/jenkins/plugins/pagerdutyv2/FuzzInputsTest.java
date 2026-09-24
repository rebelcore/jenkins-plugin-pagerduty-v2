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

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Replays the stored inputs of each fuzz target as ordinary tests, so every build re-checks the
 * seeds and every problem the fuzzer has found before. When the weekly fuzz run finds a new
 * failing input, fix the code and add the input to the target's directory under {@code
 * src/test/resources/io/jenkins/plugins/pagerdutyv2/fuzz/}.
 */
class FuzzInputsTest {

    @TestFactory
    Stream<DynamicTest> legacyBodyMigratorInputs() throws Exception {
        return replay("LegacyBodyMigratorFuzzer", LegacyBodyMigratorFuzzer::check);
    }

    @TestFactory
    Stream<DynamicTest> payloadBuilderInputs() throws Exception {
        return replay("PayloadBuilderFuzzer", PayloadBuilderFuzzer::check);
    }

    private static Stream<DynamicTest> replay(String target, Consumer<byte[]> check)
            throws IOException, URISyntaxException {
        URL dir = FuzzInputsTest.class.getResource("fuzz/" + target);
        List<Path> inputs;
        try (Stream<Path> files = Files.list(Path.of(dir.toURI()))) {
            inputs = files.sorted().toList();
        }
        assertFalse(inputs.isEmpty(), "no stored inputs for " + target);
        return inputs.stream()
                .map(input -> DynamicTest.dynamicTest(target + ": " + input.getFileName(), () -> {
                    try {
                        check.accept(Files.readAllBytes(input));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }));
    }
}
