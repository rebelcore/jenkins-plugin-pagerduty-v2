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

import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PipelineStepTest {

    @Test
    void triggersOnceWhileAnIncidentIsOpenAndResolvesIt(JenkinsRule j) throws Exception {
        try (FakeEventsApi api = new FakeEventsApi()) {
            api.configurePlugin("rk_test");
            WorkflowJob job = j.createProject(WorkflowJob.class, "pipe");
            job.setDefinition(new CpsFlowDefinition("pagerDutyV2(action: 'trigger', severity: 'error')", true));

            WorkflowRun first = j.buildAndAssertSuccess(job);
            WorkflowRun second = j.buildAndAssertSuccess(job);

            assertEquals(1, api.events().size(), "two failing builds are one incident");
            j.assertLogContains("[pagerduty-v2] Trigger sent", first);
            j.assertLogContains("Open incident already exists", second);

            job.setDefinition(new CpsFlowDefinition("pagerDutyV2(action: 'resolve')", true));
            j.buildAndAssertSuccess(job);

            List<Map<String, Object>> events = api.events();
            assertEquals(2, events.size());
            assertEquals("trigger", events.get(0).get("event_action"));
            assertEquals("error", ((Map<?, ?>) events.get(0).get("payload")).get("severity"));
            assertEquals("resolve", events.get(1).get("event_action"));
            assertEquals(events.get(0).get("dedup_key"), events.get(1).get("dedup_key"));
            assertFalse(first.getAction(PagerDutyV2RunAction.class).isOpen());
        }
    }

    @Test
    void resolveWithNothingOpenSendsNothing(JenkinsRule j) throws Exception {
        try (FakeEventsApi api = new FakeEventsApi()) {
            api.configurePlugin("rk_test");
            WorkflowJob job = j.createProject(WorkflowJob.class, "quiet");
            job.setDefinition(new CpsFlowDefinition("pagerDutyV2(action: 'resolve')", true));

            WorkflowRun run = j.buildAndAssertSuccess(job);

            j.assertLogContains("No open incident found; nothing to resolve.", run);
            assertEquals(0, api.events().size());
        }
    }
}
