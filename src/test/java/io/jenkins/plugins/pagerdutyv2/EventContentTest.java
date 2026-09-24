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

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.tasks.Builder;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.UnstableBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** What a trigger event says, depending on the job's settings. */
@WithJenkins
class EventContentTest {

    @Test
    void aCustomSummaryReplacesTheGeneratedOneUnlessItIsBlank(JenkinsRule j) throws Exception {
        try (FakeEventsApi api = new FakeEventsApi()) {
            api.configurePlugin("rk_test");
            FreeStyleProject custom =
                    project(j, new PagerDutyV2NotifierIntegrationTest.DisconnectLikeFailBuilder(), n -> {
                        n.setUseCustomSummary(true);
                        n.setCustomSummary(" Nightly deploy failed ");
                    });
            FreeStyleProject blank = project(j, new FailureBuilder(), n -> {
                n.setUseCustomSummary(true);
                n.setCustomSummary("  ");
            });

            j.buildAndAssertStatus(Result.FAILURE, custom);
            j.buildAndAssertStatus(Result.FAILURE, blank);

            List<Map<String, Object>> events = api.events();
            assertEquals(
                    "Nightly deploy failed",
                    payload(events.get(0)).get("summary"),
                    "used as written, even when the agent was lost");
            assertEquals(Boolean.TRUE, details(events.get(0)).get("executor_disconnected"));
            assertTrue(String.valueOf(payload(events.get(1)).get("summary")).startsWith("Jenkins "));
        }
    }

    @Test
    void theEndOfTheConsoleLogIsAttachedWhenAsked(JenkinsRule j) throws Exception {
        try (FakeEventsApi api = new FakeEventsApi()) {
            api.configurePlugin("rk_test");
            FreeStyleProject fewLines = project(j, new NumberedLinesThenFail(), n -> {
                n.setIncludeConsoleLogTail(true);
                n.setConsoleLogTailLines(3);
            });
            FreeStyleProject allLines = project(j, new NumberedLinesThenFail(), n -> n.setIncludeConsoleLogTail(true));
            FreeStyleProject noLines = project(j, new NumberedLinesThenFail(), n -> {});

            FreeStyleBuild b = j.buildAndAssertStatus(Result.FAILURE, fewLines);
            j.buildAndAssertStatus(Result.FAILURE, allLines);
            j.buildAndAssertStatus(Result.FAILURE, noLines);

            List<Map<String, Object>> events = api.events();
            String few = (String) details(events.get(0)).get("console_log_tail");
            String all = (String) details(events.get(1)).get("console_log_tail");
            assertTrue(few.contains("line-099"), few);
            assertFalse(few.contains("line-090"), few);
            assertEquals(4000, all.length(), "cut to the most recent 4000 characters");
            assertTrue(all.contains("line-099"));
            assertFalse(all.contains("line-000"));
            assertFalse(details(events.get(2)).containsKey("console_log_tail"));
            assertEquals("", PagerDutyV2Dispatcher.getConsoleLogTail(b, 0, 4000));
            assertEquals("", PagerDutyV2Dispatcher.getConsoleLogTail(b, 10, 0));
        }
    }

    @Test
    void sandboxModeWithoutASandboxKeyUsesThePrimaryKey(JenkinsRule j) throws Exception {
        try (FakeEventsApi api = new FakeEventsApi()) {
            api.configurePlugin("rk_primary");
            FreeStyleProject p = project(j, new FailureBuilder(), n -> n.setSandboxMode(true));

            FreeStyleBuild b = j.buildAndAssertStatus(Result.FAILURE, p);

            assertEquals("rk_primary", api.events().get(0).get("routing_key"));
            j.assertLogContains("no sandbox routing key configured; falling back to primary routing key", b);
            assertFalse(b.getAction(PagerDutyV2RunAction.class).isSandbox(), "its resolve must use the primary key");
        }
    }

    @Test
    void aJobCanAlsoAlertOnSuccessOrUnstable(JenkinsRule j) throws Exception {
        try (FakeEventsApi api = new FakeEventsApi()) {
            api.configurePlugin("rk_test");
            FreeStyleProject green = project(j, null, n -> {
                n.setTriggerOnSuccess(true);
                n.setIncludeConsoleLogTail(true);
            });
            FreeStyleProject unstable = project(j, new UnstableBuilder(), n -> n.setTriggerOnUnstable(true));

            j.buildAndAssertSuccess(green);
            j.buildAndAssertStatus(Result.UNSTABLE, unstable);

            List<Map<String, Object>> events = api.events();
            assertEquals("SUCCESS", details(events.get(0)).get("result"));
            assertFalse(details(events.get(0)).containsKey("console_log_tail"), "a green build's log is not sent");
            assertFalse(details(events.get(0)).containsKey("tags"), "no tags, no tags field");
            assertEquals("UNSTABLE", details(events.get(1)).get("result"));
        }
    }

    /** A project with the post-build action, service {@code svc}, and {@code builder} if not null. */
    private static FreeStyleProject project(JenkinsRule j, Builder builder, Consumer<PagerDutyV2Notifier> settings)
            throws IOException {
        FreeStyleProject p = j.createFreeStyleProject();
        if (builder != null) {
            p.getBuildersList().add(builder);
        }
        PagerDutyV2Notifier n = new PagerDutyV2Notifier();
        n.setService("svc");
        settings.accept(n);
        p.getPublishersList().add(n);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Map<String, Object> event) {
        return (Map<String, Object>) event.get("payload");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(Map<String, Object> event) {
        return (Map<String, Object>) payload(event).get("custom_details");
    }

    /** Logs a hundred numbered lines of about a hundred characters each, then fails the build. */
    static final class NumberedLinesThenFail extends TestBuilder {
        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
            for (int i = 0; i < 100; i++) {
                listener.getLogger().printf("line-%03d %s%n", i, "x".repeat(90));
            }
            return false;
        }
    }
}
