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
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Builds for which the post-build action deliberately sends nothing. Each still records why, so the
 * disconnect fallback knows the build was dealt with and does not send the event instead.
 */
@WithJenkins
class SkippedEventsTest {

    @Test
    void aDisabledPluginSendsNothing(JenkinsRule j) throws Exception {
        try (FakeEventsApi api = new FakeEventsApi()) {
            api.configurePlugin("rk_test");
            PagerDutyV2GlobalConfiguration.get().setDisabled(true);

            FreeStyleBuild b = j.buildAndAssertStatus(Result.FAILURE, failingProject(j, "svc"));

            assertEquals(0, api.events().size());
            j.assertLogContains("PagerDuty is disabled in system configuration; skipping.", b);
            assertEquals("disabled", reason(b));
        }
    }

    @Test
    void aJobWithoutAServiceSendsNothing(JenkinsRule j) throws Exception {
        try (FakeEventsApi api = new FakeEventsApi()) {
            api.configurePlugin("rk_test");

            FreeStyleBuild b = j.buildAndAssertStatus(Result.FAILURE, failingProject(j, ""));

            assertEquals(0, api.events().size());
            j.assertLogContains("Service is required; skipping PagerDuty notification.", b);
            assertEquals("no-service", reason(b));
        }
    }

    @Test
    void nothingIsSentWithoutARoutingKey(JenkinsRule j) throws Exception {
        try (FakeEventsApi api = new FakeEventsApi()) {
            PagerDutyV2GlobalConfiguration.get().setEndpointUrl(api.url());

            FreeStyleBuild b = j.buildAndAssertStatus(Result.FAILURE, failingProject(j, "svc"));

            assertEquals(0, api.events().size());
            j.assertLogContains("No routing key credential configured; skipping.", b);
            assertEquals("no-routing-key", reason(b));
        }
    }

    @Test
    void aSecondFailureDoesNotOpenASecondIncident(JenkinsRule j) throws Exception {
        try (FakeEventsApi api = new FakeEventsApi()) {
            api.configurePlugin("rk_test");
            FreeStyleProject p = failingProject(j, "svc");

            j.buildAndAssertStatus(Result.FAILURE, p);
            FreeStyleBuild second = j.buildAndAssertStatus(Result.FAILURE, p);

            assertEquals(1, api.events().size());
            j.assertLogContains("Open incident already exists", second);
            assertEquals("open-incident-exists", reason(second));
        }
    }

    @Test
    void aGreenBuildWithNothingOpenSendsNothing(JenkinsRule j) throws Exception {
        try (FakeEventsApi api = new FakeEventsApi()) {
            api.configurePlugin("rk_test");
            FreeStyleProject p = failingProject(j, "svc");
            p.getBuildersList().clear();

            FreeStyleBuild b = j.buildAndAssertSuccess(p);

            assertEquals(0, api.events().size());
            assertEquals("no-action", reason(b));
        }
    }

    @Test
    void anIncidentStaysOpenWhenResolvingIsTurnedOff(JenkinsRule j) throws Exception {
        try (FakeEventsApi api = new FakeEventsApi()) {
            api.configurePlugin("rk_test");
            FreeStyleProject p = failingProject(j, "svc");
            p.getPublishersList().get(PagerDutyV2Notifier.class).setResolveOnBackToNormal(false);

            FreeStyleBuild failed = j.buildAndAssertStatus(Result.FAILURE, p);
            p.getBuildersList().clear();
            FreeStyleBuild green = j.buildAndAssertSuccess(p);

            assertEquals(1, api.events().size(), "the trigger only");
            assertTrue(failed.getAction(PagerDutyV2RunAction.class).isOpen());
            assertEquals("no-action", reason(green));
        }
    }

    private static FreeStyleProject failingProject(JenkinsRule j, String service) throws IOException {
        FreeStyleProject p = j.createFreeStyleProject();
        p.getBuildersList().add(new FailureBuilder());
        PagerDutyV2Notifier n = new PagerDutyV2Notifier();
        n.setService(service);
        p.getPublishersList().add(n);
        return p;
    }

    private static String reason(FreeStyleBuild b) {
        return b.getAction(PagerDutyV2HandledAction.class).getReason();
    }
}
