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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class FailedSendTest {

    @Test
    void theFallbackDoesNotSendAgainWhenTheBuildsOwnSendFailed(JenkinsRule j) throws Exception {
        try (FakeEventsApi api = new FakeEventsApi()) {
            api.configurePlugin("rk_test");
            // 400 is not retried, which keeps the test fast.
            api.respondWith(400);
            FreeStyleProject p = j.createFreeStyleProject();
            p.getBuildersList().add(new FailureBuilder());
            PagerDutyV2Notifier n = new PagerDutyV2Notifier();
            n.setService("svc");
            n.setTriggerOnFailure(true);
            p.getPublishersList().add(n);

            FreeStyleBuild b = j.buildAndAssertStatus(Result.FAILURE, p);
            j.waitUntilNoActivity();

            assertNotNull(b.getAction(PagerDutyV2HandledAction.class), "a failed send still counts as handled");
            assertEquals(1, api.events().size(), "the event was sent once, by the build, and not again");
        }
    }

    @Test
    void aFailedSendFromTheFallbackIsLoggedAndRecordsNoIncident(JenkinsRule j) throws Exception {
        try (FakeEventsApi api = new FakeEventsApi()) {
            api.configurePlugin("rk_test");
            api.respondWith(400);
            FreeStyleProject p = j.createFreeStyleProject();
            p.getBuildersList().add(new FailureBuilder());
            // Built before the post-build action is added, as a build whose action never ran.
            FreeStyleBuild b = j.buildAndAssertStatus(Result.FAILURE, p);
            PagerDutyV2Notifier n = new PagerDutyV2Notifier();
            n.setService("svc");
            p.getPublishersList().add(n);

            new PagerDutyV2RunListener().onFinalized(b);

            assertEquals(1, api.events().size());
            assertNotNull(b.getAction(PagerDutyV2HandledAction.class));
            assertNull(b.getAction(PagerDutyV2RunAction.class), "PagerDuty refused the event, so nothing is open");
        }
    }
}
