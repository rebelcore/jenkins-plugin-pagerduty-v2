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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.InvisibleAction;

/**
 * Transient marker added to a {@link hudson.model.Run} once
 * {@link PagerDutyV2Notifier#perform} has dispatched (or deliberately skipped)
 * a PagerDuty event for that build.
 *
 * <p>Used by {@link PagerDutyV2RunListener} to avoid double-firing when the
 * publisher already ran. Not persisted: re-firing across a Jenkins restart
 * after a successful publisher run is acceptable; the dedup_key would catch
 * any duplicate at PagerDuty in any case.</p>
 */
public final class PagerDutyV2HandledAction extends InvisibleAction {

    private final transient String reason;

    public PagerDutyV2HandledAction(@CheckForNull String reason) {
        this.reason = reason;
    }

    public @CheckForNull String getReason() {
        return reason;
    }
}
