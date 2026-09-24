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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.PluginWrapper;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Starts a real Jenkins with this plugin installed. Unit tests prove the code; only this proves the
 * packaged plugin loads, which is where a wrong dependency scope or a missing index.jelly shows up.
 */
@WithJenkins
class PluginTest {

    @Test
    void loadsIntoJenkins(JenkinsRule jenkins) {
        PluginWrapper plugin = jenkins.jenkins.getPluginManager().getPlugin("pagerduty-v2");

        assertNotNull(plugin, "the plugin under test is not installed");
        assertTrue(plugin.isActive(), "the plugin under test is installed but not active");
    }
}
