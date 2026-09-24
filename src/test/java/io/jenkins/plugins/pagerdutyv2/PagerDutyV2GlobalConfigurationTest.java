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

import static io.jenkins.plugins.pagerdutyv2.Forms.assertError;
import static io.jenkins.plugins.pagerdutyv2.Forms.assertOk;
import static io.jenkins.plugins.pagerdutyv2.Forms.values;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.FormValidation;
import java.util.List;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

@WithJenkins
public class PagerDutyV2GlobalConfigurationTest {

    @Test
    void serviceChoicesParsingTrimsAndDedupes(JenkinsRule j) {
        PagerDutyV2GlobalConfiguration cfg = config(j);
        cfg.setServiceChoicesRaw(" svc-a , svc-b\nsvc-b\nsvc-c\n  \nsvc-a");

        List<String> parsed = cfg.getServiceChoices();
        assertEquals(List.of("svc-a", "svc-b", "svc-c"), parsed);
    }

    @Test
    void savingTheSystemPageKeepsEverySetting(JenkinsRule j) throws Exception {
        FakeEventsApi.addSecretText("pd-routing-key", "rk_primary");
        FakeEventsApi.addSecretText("pd-sandbox-key", "rk_sandbox");
        PagerDutyV2GlobalConfiguration cfg = config(j);
        cfg.setDisabled(true);
        cfg.setRequireTags(true);
        cfg.setEndpointUrl("https://events.example.test/v2/enqueue");
        cfg.setRoutingKeyCredentialId("pd-routing-key");
        cfg.setSandboxRoutingKeyCredentialId("pd-sandbox-key");
        cfg.setServiceChoicesRaw("svc-a\nsvc-b");

        j.configRoundtrip();

        assertTrue(cfg.isDisabled());
        assertTrue(cfg.isRequireTags());
        assertEquals("https://events.example.test/v2/enqueue", cfg.getEndpointUrl());
        assertEquals("pd-routing-key", cfg.getRoutingKeyCredentialId());
        assertEquals("pd-sandbox-key", cfg.getSandboxRoutingKeyCredentialId());
        assertEquals(List.of("svc-a", "svc-b"), cfg.getServiceChoices());

        cfg.setDisabled(false);
        cfg.setRequireTags(false);
        j.configRoundtrip();

        assertFalse(cfg.isDisabled(), "an unticked box is saved as unticked");
        assertFalse(cfg.isRequireTags());
    }

    @Test
    void unsetValuesFallBackToTheirDefaults(JenkinsRule j) {
        PagerDutyV2GlobalConfiguration cfg = config(j);

        cfg.setEndpointUrl(null);
        assertEquals(PagerDutyV2GlobalConfiguration.DEFAULT_ENDPOINT, cfg.getEndpointUrl());
        cfg.setEndpointUrl("  ");
        assertEquals(PagerDutyV2GlobalConfiguration.DEFAULT_ENDPOINT, cfg.getEndpointUrl());
        cfg.setEndpointUrl(" https://events.example.test/v2/enqueue ");
        assertEquals("https://events.example.test/v2/enqueue", cfg.getEndpointUrl());

        cfg.setServiceChoicesRaw(null);
        assertEquals("", cfg.getServiceChoicesRaw());
        assertEquals(List.of(), cfg.getServiceChoices());

        cfg.setSandboxRoutingKeyCredentialId(" ");
        assertFalse(cfg.hasSandboxRoutingKeyConfigured());
        cfg.setSandboxRoutingKeyCredentialId("pd-sandbox-key");
        assertTrue(cfg.hasSandboxRoutingKeyConfigured());
    }

    @Test
    void theEndpointMustBeAnHttpUrlWithAHost(JenkinsRule j) {
        PagerDutyV2GlobalConfiguration cfg = config(j);

        assertOk(cfg.doCheckEndpointUrl(""));
        assertOk(cfg.doCheckEndpointUrl(null));
        assertOk(cfg.doCheckEndpointUrl("https://events.pagerduty.com/v2/enqueue"));
        assertEquals(FormValidation.Kind.WARNING, cfg.doCheckEndpointUrl("http://127.0.0.1:8080/v2/enqueue").kind);
        assertError("Endpoint URL must start with http:// or https://", cfg.doCheckEndpointUrl("ftp://example.test"));
        assertError(
                "Endpoint URL must start with http:// or https://",
                cfg.doCheckEndpointUrl("events.pagerduty.com/v2/enqueue"));
        assertError("Endpoint URL must include a host.", cfg.doCheckEndpointUrl("https:///v2/enqueue"));
        assertTrue(cfg.doCheckEndpointUrl("https://exa mple.test").getMessage().startsWith("Not a valid URL: "));
    }

    @Test
    void theRoutingKeyDropdownsListOnlySecretTextCredentials(JenkinsRule j) throws Exception {
        FakeEventsApi.addSecretText("pd-routing-key", "rk_primary");
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL, "not-a-routing-key", "", "user", "a-long-password"));
        PagerDutyV2GlobalConfiguration cfg = config(j);

        List<String> primary = values(cfg.doFillRoutingKeyCredentialIdItems(""));
        List<String> sandbox = values(cfg.doFillSandboxRoutingKeyCredentialIdItems("pd-deleted-key"));

        assertTrue(primary.contains(""), "a routing key can be left unset");
        assertTrue(primary.contains("pd-routing-key"));
        assertFalse(primary.contains("not-a-routing-key"));
        assertTrue(sandbox.contains("pd-routing-key"));
        assertTrue(sandbox.contains("pd-deleted-key"), "a deleted credential stays selected until changed");
    }

    @Test
    void onlyAnAdministratorSeesTheCredentialsOrChecksTheEndpoint(JenkinsRule j) throws Exception {
        FakeEventsApi.addSecretText("pd-routing-key", "rk_primary");
        PagerDutyV2GlobalConfiguration cfg = config(j);
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().to("reader"));

        try (ACLContext ignored = ACL.as2(User.getById("reader", true).impersonate2())) {
            assertEquals(List.of("pd-current"), values(cfg.doFillRoutingKeyCredentialIdItems("pd-current")));
            assertEquals(List.of(""), values(cfg.doFillSandboxRoutingKeyCredentialIdItems(null)));
            assertThrows(AccessDeniedException.class, () -> cfg.doCheckEndpointUrl("https://example.test"));
        }
    }

    @Test
    void routingKeysAreReadFromTheirCredentials(JenkinsRule j) throws Exception {
        FakeEventsApi.addSecretText("pd-other-key", "rk_other");
        FakeEventsApi.addSecretText("pd-routing-key", "rk_primary");
        PagerDutyV2GlobalConfiguration cfg = config(j);

        assertNull(cfg.resolveRoutingKey(), "nothing is configured yet");
        cfg.setRoutingKeyCredentialId(" ");
        assertNull(cfg.resolveRoutingKey());
        cfg.setRoutingKeyCredentialId("pd-deleted-key");
        assertNull(cfg.resolveRoutingKey(), "a deleted credential gives no key");

        cfg.setRoutingKeyCredentialId("pd-routing-key");
        cfg.setSandboxRoutingKeyCredentialId("pd-other-key");

        assertEquals("rk_primary", cfg.resolveRoutingKey().getPlainText());
        assertEquals("rk_other", cfg.resolveSandboxRoutingKey().getPlainText());
    }

    private static PagerDutyV2GlobalConfiguration config(JenkinsRule j) {
        return j.jenkins.getDescriptorByType(PagerDutyV2GlobalConfiguration.class);
    }
}
