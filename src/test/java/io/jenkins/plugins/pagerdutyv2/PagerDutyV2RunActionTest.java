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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/** The incident record a trigger leaves on its build, outside of any build. */
class PagerDutyV2RunActionTest {

    @Test
    void anIncidentIsOpenUntilItIsResolved() {
        PagerDutyV2RunAction a = new PagerDutyV2RunAction("dedup", "{}");

        assertTrue(a.isOpen());
        assertFalse(a.isSandbox());
        assertEquals("dedup", a.getDedupKey());
        assertEquals("{}", a.getPayloadJson());

        a.markResolved();

        assertFalse(a.isOpen());
    }

    @Test
    void itIsNotShownOnTheBuildPage() {
        PagerDutyV2RunAction a = new PagerDutyV2RunAction("dedup", "{}", true);

        assertTrue(a.isSandbox());
        assertNull(a.getIconFileName());
        assertNull(a.getDisplayName());
        assertNull(a.getUrlName());
    }

    @Test
    void withoutABuildThereIsNothingToKeepOrRelease() {
        PagerDutyV2RunAction a = new PagerDutyV2RunAction("dedup", "{}");

        a.keepOwnerWhileOpen();
        a.stopKeepingOwner();

        assertNull(a.getOwner());
        assertTrue(a.isOpen());
    }

    @Test
    void aCurrentRecordLoadsUnchanged() {
        PagerDutyV2RunAction a = new PagerDutyV2RunAction("dedup", "{\"summary\":\"s\"}");

        assertSame(a, a.readResolve());
        assertEquals("{\"summary\":\"s\"}", a.getPayloadJson());
    }

    @Test
    void aStoredPayloadWinsOverALegacyBody() throws Exception {
        PagerDutyV2RunAction a = new PagerDutyV2RunAction("dedup", "{\"summary\":\"current\"}");
        Field legacy = PagerDutyV2RunAction.class.getDeclaredField("triggerBodyJson");
        legacy.setAccessible(true);
        legacy.set(a, "{\"routing_key\":\"rk_legacy\",\"payload\":{\"summary\":\"legacy\"}}");

        a.readResolve();

        assertEquals("{\"summary\":\"current\"}", a.getPayloadJson());
        assertNull(legacy.get(a), "the body holding the routing key is dropped all the same");
    }
}
