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
