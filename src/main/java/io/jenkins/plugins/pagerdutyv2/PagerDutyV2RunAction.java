package io.jenkins.plugins.pagerdutyv2;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Run;
import jenkins.model.RunAction2;

/**
 * Stored state for the most recent trigger event.
 *
 * We store the *exact trigger body JSON* so resolve can replay the same payload.
 */
public class PagerDutyV2RunAction implements RunAction2 {
    private transient Run<?, ?> owner;

    private String dedupKey;
    private String triggerBodyJson;
    private boolean open = true;

    public PagerDutyV2RunAction(@NonNull String dedupKey, @NonNull String triggerBodyJson) {
        this.dedupKey = dedupKey;
        this.triggerBodyJson = triggerBodyJson;
        this.open = true;
    }

    @Override
    public void onAttached(Run<?, ?> r) { this.owner = r; }

    @Override
    public void onLoad(Run<?, ?> r) { this.owner = r; }

    public @NonNull String getDedupKey() {
        return dedupKey;
    }

    public @NonNull String getTriggerBodyJson() {
        return triggerBodyJson;
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
