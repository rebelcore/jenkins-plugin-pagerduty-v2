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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LegacyBuildRecordTest {

    private static final String LEGACY_BODY = "{\"routing_key\":\"rk_legacy_secret\",\"event_action\":\"trigger\","
            + "\"dedup_key\":\"dk\",\"payload\":{\"summary\":\"s\"}}";

    @Test
    void loadingABuildFrom100RemovesTheRoutingKeyFromItsBuildXml(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("legacy");
        FreeStyleBuild b = j.buildAndAssertSuccess(p);

        // What 1.0.0 wrote to build.xml: the whole trigger body, routing key included.
        PagerDutyV2RunAction legacy = new PagerDutyV2RunAction("dk", "{}");
        setField(legacy, "payloadJson", null);
        setField(legacy, "triggerBodyJson", LEGACY_BODY);
        b.addAction(legacy);
        b.save();
        Path buildXml = b.getRootDir().toPath().resolve("build.xml");
        assertTrue(Files.readString(buildXml).contains("rk_legacy_secret"), "precondition: the key is on disk");

        j.jenkins.reload();
        FreeStyleProject reloaded = j.jenkins.getItemByFullName("legacy", FreeStyleProject.class);
        PagerDutyV2RunAction action = reloaded.getBuildByNumber(b.getNumber()).getAction(PagerDutyV2RunAction.class);

        assertNotNull(action);
        assertEquals("{\"summary\":\"s\"}", action.getPayloadJson());
        String onDisk = Files.readString(buildXml);
        assertFalse(onDisk.contains("rk_legacy_secret"), "the routing key must be gone from build.xml once loaded");
        assertFalse(onDisk.contains("triggerBodyJson"));
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
