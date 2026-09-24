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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.tasks.Builder;
import hudson.tasks.LogRotator;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * An open incident is recorded on the build that triggered it. If build retention deleted that
 * build, the incident could never be resolved and the next failure would open a second one.
 */
@WithJenkins
class BuildRetentionTest {

    @Test
    void theBuildThatOpenedAnIncidentIsKeptUntilItIsResolved(JenkinsRule j) throws Exception {
        try (FakeEventsApi api = new FakeEventsApi()) {
            api.configurePlugin("rk_test");
            FreeStyleProject p = j.createFreeStyleProject();
            // Keep only the newest build.
            p.setBuildDiscarder(new LogRotator(-1, 1, -1, -1));
            p.getBuildersList().add(new FailureBuilder());
            PagerDutyV2Notifier n = new PagerDutyV2Notifier();
            n.setService("svc");
            n.setTriggerOnFailure(true);
            n.setResolveOnBackToNormal(true);
            p.getPublishersList().add(n);

            FreeStyleBuild first = j.buildAndAssertStatus(Result.FAILURE, p);
            j.buildAndAssertStatus(Result.FAILURE, p);
            j.buildAndAssertStatus(Result.FAILURE, p);

            assertEquals(1, api.events().size(), "one incident, however many failures follow");
            assertNotNull(p.getBuildByNumber(first.getNumber()), "the build holding the open incident is kept");
            assertTrue(first.isKeepLog());

            p.getBuildersList().clear();
            j.buildAndAssertSuccess(p);

            List<Map<String, Object>> events = api.events();
            assertEquals(2, events.size());
            assertEquals("resolve", events.get(1).get("event_action"));
            assertEquals(events.get(0).get("dedup_key"), events.get(1).get("dedup_key"));
            assertFalse(first.isKeepLog(), "once resolved, build retention applies to it again");
        }
    }

    @Test
    void aBuildSomeoneAlreadyKeepsStaysKeptAfterTheResolve(JenkinsRule j) throws Exception {
        try (FakeEventsApi api = new FakeEventsApi()) {
            api.configurePlugin("rk_test");
            FreeStyleProject p = j.createFreeStyleProject();
            // Build steps stop at the first failure, so the keep has to come first.
            p.getBuildersList().add(new KeepForeverBuilder());
            p.getBuildersList().add(new FailureBuilder());
            PagerDutyV2Notifier n = new PagerDutyV2Notifier();
            n.setService("svc");
            n.setTriggerOnFailure(true);
            n.setResolveOnBackToNormal(true);
            p.getPublishersList().add(n);

            FreeStyleBuild first = j.buildAndAssertStatus(Result.FAILURE, p);
            p.getBuildersList().clear();
            j.buildAndAssertSuccess(p);

            assertEquals(2, api.events().size());
            assertTrue(first.isKeepLog(), "the plugin must not undo a keep it did not set");
        }
    }

    /** Marks its build "keep forever", as a user would, before the post-build action runs. */
    static final class KeepForeverBuilder extends Builder {
        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws IOException {
            build.keepLog(true);
            return true;
        }
    }
}
