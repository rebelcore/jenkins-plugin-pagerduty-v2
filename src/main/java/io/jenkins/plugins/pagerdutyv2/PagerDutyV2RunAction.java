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
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.RunAction2;

/**
 * Stored state for the most recent trigger event.
 *
 * We persist the trigger {@code payload} JSON (and dedup key) so resolve can replay
 * the same body to PagerDuty. The {@code routing_key} is intentionally NOT stored
 * — it is a secret and must be resolved from credentials at send time.
 */
public final class PagerDutyV2RunAction implements RunAction2 {
    private static final Logger LOGGER = Logger.getLogger(PagerDutyV2RunAction.class.getName());

    private transient Run<?, ?> owner;

    /**
     * Set by {@link #readResolve()} when it dropped a legacy body, so that {@link #onLoad} writes the
     * build record again without it.
     */
    private transient boolean legacyBodyDropped;

    private String dedupKey;
    /** JSON-serialized {@code payload} object only — never the full body. */
    private String payloadJson;

    private boolean open = true;

    /**
     * Legacy field: full trigger body JSON including {@code routing_key}.
     * Persisted by 1.0.0; kept here only so XStream can read those
     * {@code build.xml} files. {@link #readResolve()} moves the payload to
     * {@link #payloadJson} and clears it, and {@link #onLoad} then saves the
     * build so the routing key is gone from disk as well as from memory.
     */
    @Deprecated
    private String triggerBodyJson;

    public PagerDutyV2RunAction(@NonNull String dedupKey, @NonNull String payloadJson) {
        this.dedupKey = dedupKey;
        this.payloadJson = payloadJson;
        this.open = true;
    }

    @Override
    public void onAttached(Run<?, ?> r) {
        this.owner = r;
    }

    @Override
    public void onLoad(Run<?, ?> r) {
        this.owner = r;
        if (legacyBodyDropped) {
            // Until the build is saved, its build.xml still holds the routing key that 1.0.0
            // wrote, and nothing else would save an old, finished build again.
            legacyBodyDropped = false;
            try {
                r.save();
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Could not rewrite " + r + " to remove the routing key 1.0.0 stored", e);
            }
        }
    }

    public @NonNull String getDedupKey() {
        return dedupKey;
    }

    public @NonNull String getPayloadJson() {
        return payloadJson;
    }

    public @CheckForNull Run<?, ?> getOwner() {
        return owner;
    }

    public boolean isOpen() {
        return open;
    }

    public void markResolved() {
        this.open = false;
    }

    /**
     * Migrate legacy {@code triggerBodyJson} (which contained the routing key)
     * into {@link #payloadJson} (payload only) on load.
     */
    @SuppressWarnings("deprecation")
    protected Object readResolve() {
        if (triggerBodyJson != null) {
            if (payloadJson == null) {
                payloadJson = LegacyBodyMigrator.extractPayload(triggerBodyJson);
            }
            triggerBodyJson = null;
            legacyBodyDropped = true;
        }
        return this;
    }

    @Override
    public @CheckForNull String getIconFileName() {
        return null; // hidden
    }

    @Override
    public @CheckForNull String getDisplayName() {
        return null; // hidden
    }

    @Override
    public @CheckForNull String getUrlName() {
        return null; // hidden
    }
}
