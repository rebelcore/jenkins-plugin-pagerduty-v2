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
import hudson.EnvVars;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Jazzer fuzz target for {@link PayloadBuilder#dedupKey}. Job names and build numbers are whatever
 * Jenkins hands the build, including any characters a folder or branch name can hold. The dedup key
 * made from them must always have the same shape, because PagerDuty matches a resolve to its
 * trigger by it.
 *
 * <p>The input is the job name and the build number, separated by the first zero byte. The weekly
 * fuzz workflow runs this with Jazzer. {@link FuzzInputsTest} replays every input under {@code
 * fuzz/PayloadBuilderFuzzer} in each build.
 */
public final class PayloadBuilderFuzzer {

    private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-f]{64}");

    private PayloadBuilderFuzzer() {}

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        check(data.consumeRemainingAsBytes());
    }

    static void check(byte[] input) {
        int split = indexOfZero(input);
        EnvVars env = new EnvVars();
        env.put("JOB_NAME", new String(Arrays.copyOfRange(input, 0, split), StandardCharsets.UTF_8));
        env.put(
                "BUILD_NUMBER",
                new String(
                        Arrays.copyOfRange(input, Math.min(split + 1, input.length), input.length),
                        StandardCharsets.UTF_8));

        String dedupKey = PayloadBuilder.dedupKey(env);
        if (!SHA_256_HEX.matcher(dedupKey).matches()) {
            throw new IllegalStateException("dedup key is not 64 lowercase hex characters: " + dedupKey);
        }
    }

    private static int indexOfZero(byte[] input) {
        for (int i = 0; i < input.length; i++) {
            if (input[i] == 0) {
                return i;
            }
        }
        return input.length;
    }
}
