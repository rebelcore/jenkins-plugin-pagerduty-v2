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

import static io.jenkins.plugins.pagerdutyv2.Forms.selected;
import static io.jenkins.plugins.pagerdutyv2.Forms.values;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The post-build action's settings, as the job form, Job DSL and JCasC set them. */
class PagerDutyV2NotifierTest {

    @Test
    void aNewActionAlertsOnFailureAndResolvesOnSuccess() {
        PagerDutyV2Notifier n = new PagerDutyV2Notifier();

        assertEquals("error", n.getSeverityOnFailure());
        assertEquals("", n.getService());
        assertEquals("", n.getTags());
        assertFalse(n.isSandboxMode());
        assertEquals(1, n.getConsecutiveBuildsBeforeTrigger());
        assertTrue(n.isTriggerOnFailure());
        assertFalse(n.isTriggerOnSuccess());
        assertFalse(n.isTriggerOnUnstable());
        assertFalse(n.isTriggerOnAbort());
        assertFalse(n.isTriggerOnNotBuilt());
        assertTrue(n.isResolveOnBackToNormal());
        assertFalse(n.isIncludeConsoleLogTail());
        assertEquals(200, n.getConsoleLogTailLines());
        assertFalse(n.isUseCustomSummary());
        assertEquals("", n.getCustomSummary());
        assertFalse(n.requiresWorkspace(), "it must run for a build whose agent is gone");
    }

    @Test
    void textSettingsAreTrimmedAndNeverNull() {
        PagerDutyV2Notifier n = new PagerDutyV2Notifier();
        n.setService("  svc-a ");
        n.setTags(" team=ops ");
        n.setSeverityOnFailure(" warning ");

        assertEquals("svc-a", n.getService());
        assertEquals("team=ops", n.getTags());
        assertEquals("warning", n.getSeverityOnFailure());

        n.setService(null);
        n.setTags(null);
        n.setCustomSummary(null);

        assertEquals("", n.getService());
        assertEquals("", n.getTags());
        assertEquals("", n.getCustomSummary());
    }

    @Test
    void aBlankSeverityKeepsTheCurrentOne() {
        PagerDutyV2Notifier n = new PagerDutyV2Notifier();
        n.setSeverityOnFailure("critical");

        n.setSeverityOnFailure("  ");
        n.setSeverityOnFailure(null);

        assertEquals("critical", n.getSeverityOnFailure());
    }

    @Test
    void countsBelowOneAreRaisedToOne() {
        PagerDutyV2Notifier n = new PagerDutyV2Notifier();
        n.setConsecutiveBuildsBeforeTrigger(0);
        n.setConsoleLogTailLines(-5);

        assertEquals(1, n.getConsecutiveBuildsBeforeTrigger());
        assertEquals(1, n.getConsoleLogTailLines());
    }

    @Test
    void theSeverityDropdownSelectsTheCurrentSeverityOrError() {
        PagerDutyV2Notifier.DescriptorImpl d = new PagerDutyV2Notifier.DescriptorImpl();

        assertEquals(List.of("critical", "error", "warning", "info"), values(d.doFillSeverityOnFailureItems("info")));
        assertEquals("warning", selected(d.doFillSeverityOnFailureItems(" warning ")));
        assertEquals("error", selected(d.doFillSeverityOnFailureItems("")));
        assertEquals("error", selected(d.doFillSeverityOnFailureItems(null)));
    }

    @Test
    void itIsOfferedToEveryKindOfJob() {
        PagerDutyV2Notifier.DescriptorImpl d = new PagerDutyV2Notifier.DescriptorImpl();

        assertTrue(d.isApplicable(FreeStyleProject.class));
        assertEquals("PagerDuty v2", d.getDisplayName());
    }
}
