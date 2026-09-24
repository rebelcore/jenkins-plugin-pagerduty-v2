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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * An incident lives in the integration its trigger was sent to. Its resolve has to go to the same
 * one, whatever the job's sandbox setting says by the time the build is green again.
 */
@WithJenkins
class SandboxRoutingKeyTest {

    @Test
    void theResolveUsesTheRoutingKeyItsTriggerUsed(JenkinsRule j) throws Exception {
        try (FakeEventsApi api = new FakeEventsApi()) {
            FreeStyleProject p = sandboxProject(j, api);

            j.buildAndAssertStatus(Result.FAILURE, p);
            p.getPublishersList().get(PagerDutyV2Notifier.class).setSandboxMode(false);
            p.getBuildersList().clear();
            j.buildAndAssertSuccess(p);

            List<Map<String, Object>> events = api.events();
            assertEquals(2, events.size());
            assertEquals("rk_sandbox", events.get(0).get("routing_key"));
            assertEquals("resolve", events.get(1).get("event_action"));
            assertEquals("rk_sandbox", events.get(1).get("routing_key"));
        }
    }

    @Test
    void theIncidentStaysOpenWhenItsRoutingKeyIsGone(JenkinsRule j) throws Exception {
        try (FakeEventsApi api = new FakeEventsApi()) {
            FreeStyleProject p = sandboxProject(j, api);

            FreeStyleBuild first = j.buildAndAssertStatus(Result.FAILURE, p);
            PagerDutyV2GlobalConfiguration.get().setSandboxRoutingKeyCredentialId(null);
            p.getBuildersList().clear();
            FreeStyleBuild green = j.buildAndAssertSuccess(p);

            assertEquals(1, api.events().size(), "no resolve can reach the incident's integration");
            assertTrue(first.getAction(PagerDutyV2RunAction.class).isOpen());
            j.assertLogContains("not resolving", green);
        }
    }

    private static FreeStyleProject sandboxProject(JenkinsRule j, FakeEventsApi api) throws Exception {
        api.configurePlugin("rk_primary");
        FakeEventsApi.addSecretText("pd-sandbox-key", "rk_sandbox");
        PagerDutyV2GlobalConfiguration.get().setSandboxRoutingKeyCredentialId("pd-sandbox-key");
        FreeStyleProject p = j.createFreeStyleProject();
        p.getBuildersList().add(new FailureBuilder());
        PagerDutyV2Notifier n = new PagerDutyV2Notifier();
        n.setService("svc");
        n.setSandboxMode(true);
        n.setTriggerOnFailure(true);
        n.setResolveOnBackToNormal(true);
        p.getPublishersList().add(n);
        return p;
    }
}
