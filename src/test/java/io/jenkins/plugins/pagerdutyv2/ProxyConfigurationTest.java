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

import hudson.ProxyConfiguration;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Events follow the Jenkins proxy configuration for the host they are actually sent to, so an
 * endpoint on the no-proxy list is reached directly.
 */
@WithJenkins
class ProxyConfigurationTest {

    @Test
    void anEndpointOnTheNoProxyListIsReachedDirectly(JenkinsRule j) throws Exception {
        try (FakeEventsApi api = new FakeEventsApi()) {
            api.configurePlugin("rk_test");
            // A proxy that is not listening: anything sent through it fails.
            j.jenkins.setProxy(new ProxyConfiguration("127.0.0.1", unusedPort(), null, null, "127.0.0.1"));
            FreeStyleProject p = j.createFreeStyleProject();
            p.getBuildersList().add(new FailureBuilder());
            PagerDutyV2Notifier n = new PagerDutyV2Notifier();
            n.setService("svc");
            n.setTriggerOnFailure(true);
            p.getPublishersList().add(n);

            j.buildAndAssertStatus(Result.FAILURE, p);

            assertEquals(1, api.events().size(), "sent straight to the endpoint, not through the proxy");
        }
    }

    private static int unusedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
