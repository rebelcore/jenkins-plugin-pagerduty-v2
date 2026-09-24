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

import hudson.EnvVars;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The {@code pagerDutyV2} step's arguments, and how the Pipeline Syntax page offers and checks them. */
class PagerDutyV2StepTest {

    @Test
    void aStepWithoutArgumentsTriggersACriticalIncident() {
        PagerDutyV2Step step = new PagerDutyV2Step();

        assertEquals("trigger", step.getAction());
        assertEquals("critical", step.getSeverity());
    }

    @Test
    void argumentsAreTrimmedAndLowerCased() {
        PagerDutyV2Step step = new PagerDutyV2Step();
        step.setAction(" RESOLVE ");
        step.setSeverity(" Warning ");

        assertEquals("resolve", step.getAction());
        assertEquals("warning", step.getSeverity());
    }

    @Test
    void aBlankArgumentKeepsTheCurrentValue() {
        PagerDutyV2Step step = new PagerDutyV2Step();
        step.setAction("resolve");
        step.setSeverity("info");

        step.setAction(" ");
        step.setAction(null);
        step.setSeverity("");
        step.setSeverity(null);

        assertEquals("resolve", step.getAction());
        assertEquals("info", step.getSeverity());
    }

    @Test
    void theStepIsCalledPagerDutyV2AndRunsInsideABuild() {
        PagerDutyV2Step.DescriptorImpl d = new PagerDutyV2Step.DescriptorImpl();

        assertEquals("pagerDutyV2", d.getFunctionName());
        assertEquals("Send PagerDuty Events API v2 (trigger/resolve)", d.getDisplayName());
        assertEquals(Set.of(Run.class, TaskListener.class, EnvVars.class), d.getRequiredContext());
    }

    @Test
    void theDropdownsOfferEveryActionAndSeverity() {
        PagerDutyV2Step.DescriptorImpl d = new PagerDutyV2Step.DescriptorImpl();

        assertEquals(PagerDutyV2Step.VALID_ACTIONS, Set.copyOf(values(d.doFillActionItems())));
        assertEquals(PagerDutyV2Step.VALID_SEVERITIES, Set.copyOf(values(d.doFillSeverityItems())));
    }

    @Test
    void anActionIsRequiredAndMustBeTriggerOrResolve() {
        PagerDutyV2Step.DescriptorImpl d = new PagerDutyV2Step.DescriptorImpl();

        assertError("Action is required.", d.doCheckAction(" "));
        assertError("Action is required.", d.doCheckAction(null));
        assertError("Action must be one of: trigger, resolve.", d.doCheckAction("page"));
        for (String ok : List.of("trigger", " Resolve ")) {
            assertOk(d.doCheckAction(ok));
        }
    }

    @Test
    void aSeverityIsOptionalButMustBeKnown() {
        PagerDutyV2Step.DescriptorImpl d = new PagerDutyV2Step.DescriptorImpl();

        assertError("Severity must be one of: critical, error, warning, info.", d.doCheckSeverity("urgent"));
        assertOk(d.doCheckSeverity(null));
        for (String ok : List.of("", "INFO", " error ")) {
            assertOk(d.doCheckSeverity(ok));
        }
    }
}
