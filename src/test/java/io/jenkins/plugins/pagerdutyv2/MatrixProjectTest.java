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
import hudson.matrix.AxisList;
import hudson.matrix.MatrixProject;
import hudson.matrix.TextAxis;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.Builder;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * In a matrix job the post-build action runs in every configuration. The parent build runs no
 * publishers of its own, so it must not look to the disconnect fallback like one that was skipped.
 */
@WithJenkins
class MatrixProjectTest {

    @Test
    void onlyTheFailingConfigurationSendsAnEvent(JenkinsRule j) throws Exception {
        try (FakeEventsApi api = new FakeEventsApi()) {
            api.configurePlugin("rk_test");
            MatrixProject p = j.createProject(MatrixProject.class, "mx");
            p.setAxes(new AxisList(new TextAxis("axis", "a", "b")));
            p.getBuildersList().add(new FailWhenAxisIs("a"));
            PagerDutyV2Notifier n = new PagerDutyV2Notifier();
            n.setService("svc");
            n.setTriggerOnFailure(true);
            p.getPublishersList().add(n);

            j.buildAndAssertStatus(Result.FAILURE, p);
            j.waitUntilNoActivity();

            assertEquals(1, api.events().size(), "one event, from the axis=a configuration");
        }
    }

    /** Fails the configurations whose {@code axis} value is {@code failing}, and passes the rest. */
    static final class FailWhenAxisIs extends Builder {
        private final String failing;

        FailWhenAxisIs(String failing) {
            this.failing = failing;
        }

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
            return !failing.equals(build.getBuildVariables().get("axis"));
        }
    }
}
