package io.jenkins.plugins.pagerdutyv2;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Run;
import jenkins.model.RunAction2;

/**
 * Stored state for the most recent trigger event.
 *
 * We persist the trigger {@code payload} JSON (and dedup key) so resolve can replay
 * the same body to PagerDuty. The {@code routing_key} is intentionally NOT stored
 * — it is a secret and must be resolved from credentials at send time.
 */
public class PagerDutyV2RunAction implements RunAction2 {
    private transient Run<?, ?> owner;

    private String dedupKey;
    /** JSON-serialized {@code payload} object only — never the full body. */
    private String payloadJson;
    private boolean open = true;

    /**
     * Legacy field: full trigger body JSON including {@code routing_key}.
     * Persisted by older versions; kept here only so XStream can read existing
     * {@code build.xml} files. Migrated to {@link #payloadJson} on first read
     * via {@link #readResolve()}, after which it is cleared.
     */
    @Deprecated
    private String triggerBodyJson;

    public PagerDutyV2RunAction(@NonNull String dedupKey, @NonNull String payloadJson) {
        this.dedupKey = dedupKey;
        this.payloadJson = payloadJson;
        this.open = true;
    }

    @Override
    public void onAttached(Run<?, ?> r) { this.owner = r; }

    @Override
    public void onLoad(Run<?, ?> r) { this.owner = r; }

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
        if (payloadJson == null && triggerBodyJson != null) {
            payloadJson = LegacyBodyMigrator.extractPayload(triggerBodyJson);
        }
        triggerBodyJson = null;
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
