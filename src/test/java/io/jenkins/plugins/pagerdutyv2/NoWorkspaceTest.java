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

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.slaves.DumbSlave;
import hudson.tasks.Builder;
import java.io.IOException;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * A build whose agent is gone by post-build time has no workspace. The post-build action never
 * used one, so it must still run, rather than fail the build with "no workspace".
 */
@WithJenkins
class NoWorkspaceTest {

    @Test
    void aGreenBuildStaysGreenWhenItsAgentIsGone(JenkinsRule j) throws Exception {
        try (FakeEventsApi api = new FakeEventsApi()) {
            api.configurePlugin("rk_test");
            FreeStyleProject p = projectOnAgentThatDisappears(j, true);

            FreeStyleBuild b = j.buildAndAssertSuccess(p);

            j.assertLogNotContains("no workspace", b);
            assertEquals(0, api.events().size());
        }
    }

    @Test
    void theBuildItselfSendsTheEventWhenItsAgentIsGone(JenkinsRule j) throws Exception {
        try (FakeEventsApi api = new FakeEventsApi()) {
            api.configurePlugin("rk_test");
            FreeStyleProject p = projectOnAgentThatDisappears(j, false);

            FreeStyleBuild b = j.buildAndAssertStatus(Result.FAILURE, p);

            // The fallback listener logs to the system log; only the post-build action writes here.
            j.assertLogContains("[pagerduty-v2] Trigger sent", b);
            j.assertLogNotContains("no workspace", b);
            assertEquals(1, api.events().size());
        }
    }

    private static FreeStyleProject projectOnAgentThatDisappears(JenkinsRule j, boolean succeed) throws Exception {
        DumbSlave agent = j.createOnlineSlave();
        FreeStyleProject p = j.createFreeStyleProject();
        p.setAssignedNode(agent);
        p.getBuildersList().add(new RemoveNodeBuilder(succeed));
        PagerDutyV2Notifier n = new PagerDutyV2Notifier();
        n.setService("svc");
        n.setTriggerOnFailure(true);
        p.getPublishersList().add(n);
        return p;
    }

    /**
     * Removes the node the build runs on, which is what an agent disconnect looks like to the
     * post-build phase: the build no longer has a workspace.
     */
    static final class RemoveNodeBuilder extends Builder {
        private final boolean succeed;

        RemoveNodeBuilder(boolean succeed) {
            this.succeed = succeed;
        }

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws IOException {
            Jenkins.get().removeNode(build.getBuiltOn());
            return succeed;
        }
    }
}
