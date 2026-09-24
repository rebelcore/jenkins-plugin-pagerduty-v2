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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The decisions the dispatcher makes from a build's result and console output, without a build. */
class PagerDutyV2DispatcherTest {

    @Test
    void byDefaultOnlyAFailureTriggers() {
        PagerDutyV2Dispatcher.Config c = new PagerDutyV2Dispatcher.Config(new PagerDutyV2Notifier());

        assertTrue(PagerDutyV2Dispatcher.shouldTriggerFor(c, Result.FAILURE));
        assertFalse(PagerDutyV2Dispatcher.shouldTriggerFor(c, Result.SUCCESS));
        assertFalse(PagerDutyV2Dispatcher.shouldTriggerFor(c, Result.UNSTABLE));
        assertFalse(PagerDutyV2Dispatcher.shouldTriggerFor(c, Result.ABORTED));
        assertFalse(PagerDutyV2Dispatcher.shouldTriggerFor(c, Result.NOT_BUILT));
    }

    @Test
    void eachResultTriggersWhenItsSettingIsOn() {
        PagerDutyV2Notifier n = new PagerDutyV2Notifier();
        n.setTriggerOnSuccess(true);
        n.setTriggerOnFailure(false);
        n.setTriggerOnUnstable(true);
        n.setTriggerOnAbort(true);
        n.setTriggerOnNotBuilt(true);
        PagerDutyV2Dispatcher.Config c = new PagerDutyV2Dispatcher.Config(n);

        assertTrue(PagerDutyV2Dispatcher.shouldTriggerFor(c, Result.SUCCESS));
        assertFalse(PagerDutyV2Dispatcher.shouldTriggerFor(c, Result.FAILURE));
        assertTrue(PagerDutyV2Dispatcher.shouldTriggerFor(c, Result.UNSTABLE));
        assertTrue(PagerDutyV2Dispatcher.shouldTriggerFor(c, Result.ABORTED));
        assertTrue(PagerDutyV2Dispatcher.shouldTriggerFor(c, Result.NOT_BUILT));
    }

    // One line per message Jenkins logs when a build loses its agent. Each line matches only its
    // own signature, so every signature is shown to work on its own.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "java.nio.channels.ClosedChannelException",
                "java.io.IOException: Backing channel 'agent-1' is disconnected.",
                "Agent agent-1 dropped: JNLP4-connect connection from 10.0.0.7/10.0.0.7:34716 closed",
                "hudson.remoting.ChannelClosedException: Channel \"unknown\": Remote call failed",
                "Agent went offline during the build",
                "ERROR: Executor was removed from agent-1",
                "java.io.IOException: Connection was broken",
            })
    void aLostAgentIsRecognisedFromTheConsole(String line) {
        assertTrue(PagerDutyV2Dispatcher.hasDisconnectSignature(
                "Building remotely on agent-1\n" + line + "\nFinished: FAILURE"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "ERROR: script returned exit code 1",
                "Backing channel 'agent-1' is healthy",
                "The agent is disconnected from the VPN but the build is not affected",
            })
    void anOrdinaryFailureIsNotMistakenForALostAgent(String console) {
        assertFalse(PagerDutyV2Dispatcher.hasDisconnectSignature(console));
    }
}
