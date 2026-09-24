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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Descriptor;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.ListBoxModel;
import java.util.List;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.Stapler;
import org.springframework.security.access.AccessDeniedException;

/** The post-build action's section of the job configuration page. */
@WithJenkins
class JobConfigurationTest {

    @Test
    void savingTheJobPageKeepsEverySetting(JenkinsRule j) throws Exception {
        // The sandbox checkbox is only on the page once a sandbox key is configured.
        FakeEventsApi.addSecretText("pd-sandbox-key", "rk_sandbox");
        PagerDutyV2GlobalConfiguration.get().setSandboxRoutingKeyCredentialId("pd-sandbox-key");
        PagerDutyV2Notifier n = new PagerDutyV2Notifier();
        n.setService("svc-a");
        n.setTags("team=ops");
        n.setSeverityOnFailure("warning");
        n.setSandboxMode(true);
        n.setConsecutiveBuildsBeforeTrigger(3);
        n.setTriggerOnSuccess(true);
        n.setTriggerOnFailure(false);
        n.setTriggerOnUnstable(true);
        n.setTriggerOnAbort(true);
        n.setTriggerOnNotBuilt(true);
        n.setResolveOnBackToNormal(false);
        n.setIncludeConsoleLogTail(true);
        n.setConsoleLogTailLines(50);
        n.setUseCustomSummary(true);
        n.setCustomSummary("Nightly deploy failed");
        FreeStyleProject p = j.createFreeStyleProject();
        p.getPublishersList().add(n);

        j.configRoundtrip(p);

        j.assertEqualDataBoundBeans(n, p.getPublishersList().get(PagerDutyV2Notifier.class));
    }

    @Test
    void savingTheJobPageKeepsAServiceChosenFromTheList(JenkinsRule j) throws Exception {
        PagerDutyV2GlobalConfiguration.get().setServiceChoicesRaw("svc-a\nsvc-b");
        PagerDutyV2Notifier n = new PagerDutyV2Notifier();
        n.setService("svc-b");
        FreeStyleProject p = j.createFreeStyleProject();
        p.getPublishersList().add(n);

        j.configRoundtrip(p);

        assertEquals(
                "svc-b", p.getPublishersList().get(PagerDutyV2Notifier.class).getService());
    }

    @Test
    void theOptionalSectionsOfTheFormAreRead(JenkinsRule j) throws Exception {
        PagerDutyV2Notifier filled = submit(
                j,
                form("svc-a")
                        .element("includeConsoleLogTail", new JSONObject().element("consoleLogTailLines", 50))
                        .element("useCustomSummary", new JSONObject().element("customSummary", "Deploy failed")));
        PagerDutyV2Notifier empty = submit(
                j,
                form("svc-a")
                        .element("includeConsoleLogTail", new JSONObject())
                        .element("useCustomSummary", new JSONObject()));

        assertTrue(filled.isIncludeConsoleLogTail());
        assertEquals(50, filled.getConsoleLogTailLines());
        assertTrue(filled.isUseCustomSummary());
        assertEquals("Deploy failed", filled.getCustomSummary());
        assertTrue(empty.isIncludeConsoleLogTail());
        assertEquals(200, empty.getConsoleLogTailLines());
        assertTrue(empty.isUseCustomSummary());
        assertEquals("", empty.getCustomSummary());
    }

    @Test
    void aJobWithoutAServiceIsNotSaved(JenkinsRule j) {
        Descriptor.FormException e = assertThrows(Descriptor.FormException.class, () -> submit(j, form("  ")));

        assertEquals("Service is required.", e.getMessage());
        assertEquals("service", e.getFormField());
    }

    @Test
    void theServiceMustBeOneFromTheList(JenkinsRule j) throws Exception {
        PagerDutyV2GlobalConfiguration.get().setServiceChoicesRaw("svc-a, svc-b");

        Descriptor.FormException none = assertThrows(Descriptor.FormException.class, () -> submit(j, form("")));
        Descriptor.FormException other = assertThrows(Descriptor.FormException.class, () -> submit(j, form("svc-z")));

        assertEquals("Please select a service.", none.getMessage());
        assertEquals("Service must be one of the values defined in System Configuration.", other.getMessage());
        assertEquals("svc-b", submit(j, form("svc-b")).getService());
    }

    @Test
    void tagsAreRequiredOnlyWhenTheSystemConfigurationSaysSo(JenkinsRule j) throws Exception {
        assertEquals("", submit(j, form("svc-a")).getTags());

        PagerDutyV2GlobalConfiguration.get().setRequireTags(true);
        Descriptor.FormException e = assertThrows(Descriptor.FormException.class, () -> submit(j, form("svc-a")));

        assertEquals("Tags are required.", e.getMessage());
        assertEquals("tags", e.getFormField());
        assertEquals(
                "team=ops", submit(j, form("svc-a").element("tags", "team=ops")).getTags());
    }

    @Test
    void theServiceDropdownOffersTheListAndKeepsARetiredService(JenkinsRule j) {
        PagerDutyV2Notifier.DescriptorImpl d = descriptor(j);
        assertFalse(d.hasServiceChoices());
        assertEquals(List.of(""), values(d.doFillServiceItems("")));

        PagerDutyV2GlobalConfiguration.get().setServiceChoicesRaw("svc-a, svc-b");
        ListBoxModel retired = d.doFillServiceItems("svc-old");

        assertTrue(d.hasServiceChoices());
        assertEquals(List.of("", "svc-a", "svc-b"), values(d.doFillServiceItems(null)));
        assertEquals(List.of("", "svc-a", "svc-b"), values(d.doFillServiceItems("svc-b")));
        assertEquals(List.of("svc-old", "", "svc-a", "svc-b"), values(retired));
        assertEquals("svc-old (current)", retired.get(0).name);
    }

    @Test
    void theFieldChecksFollowTheSystemConfiguration(JenkinsRule j) throws Exception {
        PagerDutyV2Notifier.DescriptorImpl d = descriptor(j);
        FreeStyleProject p = j.createFreeStyleProject();
        assertError("Service is required.", d.doCheckService(p, " "));
        assertOk(d.doCheckService(p, "any-service"));
        assertOk(d.doCheckTags(p, ""));
        assertFalse(d.isTagsRequired());
        assertFalse(d.hasSandboxRoutingKeyConfigured());

        PagerDutyV2GlobalConfiguration cfg = PagerDutyV2GlobalConfiguration.get();
        cfg.setServiceChoicesRaw("svc-a");
        cfg.setRequireTags(true);
        cfg.setSandboxRoutingKeyCredentialId("pd-sandbox-key");

        assertError("Please select a service.", d.doCheckService(p, null));
        assertError("Service must be one of the values defined in System Configuration.", d.doCheckService(p, "svc-z"));
        assertOk(d.doCheckService(p, " svc-a "));
        assertError("Tags are required.", d.doCheckTags(p, " "));
        assertError("Tags are required.", d.doCheckTags(p, null));
        assertOk(d.doCheckTags(p, "team=ops"));
        assertTrue(d.isTagsRequired());
        assertTrue(d.hasSandboxRoutingKeyConfigured());
    }

    @Test
    void theFieldChecksNeedPermissionToConfigureTheJob(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        PagerDutyV2Notifier.DescriptorImpl d = descriptor(j);
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .to("reader")
                .grant(Jenkins.READ, Item.READ, Item.CONFIGURE)
                .everywhere()
                .to("configurer"));

        try (ACLContext ignored = ACL.as2(User.getById("reader", true).impersonate2())) {
            assertThrows(AccessDeniedException.class, () -> d.doCheckService(p, "svc-a"));
            assertThrows(AccessDeniedException.class, () -> d.doCheckTags(p, "team=ops"));
        }
        try (ACLContext ignored = ACL.as2(User.getById("configurer", true).impersonate2())) {
            assertOk(d.doCheckService(p, "svc-a"));
            // Outside a job, only an administrator may use the checks.
            assertThrows(AccessDeniedException.class, () -> d.doCheckService(null, "svc-a"));
        }
    }

    private static PagerDutyV2Notifier.DescriptorImpl descriptor(JenkinsRule j) {
        return j.jenkins.getDescriptorByType(PagerDutyV2Notifier.DescriptorImpl.class);
    }

    private static JSONObject form(String service) {
        return new JSONObject().element("service", service);
    }

    /** Reads {@code form} the way saving the job page does, inside a real request. */
    private static PagerDutyV2Notifier submit(JenkinsRule j, JSONObject form) throws Exception {
        PagerDutyV2Notifier.DescriptorImpl d = descriptor(j);
        return j.executeOnServer(() -> d.newInstance(Stapler.getCurrentRequest2(), form));
    }
}
