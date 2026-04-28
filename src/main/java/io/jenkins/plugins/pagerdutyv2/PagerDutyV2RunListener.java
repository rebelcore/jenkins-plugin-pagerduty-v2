package io.jenkins.plugins.pagerdutyv2;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.tasks.Publisher;
import hudson.util.DescribableList;
import hudson.util.LogTaskListener;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controller-side fallback that fires PagerDuty events for builds whose
 * publisher phase didn't run — most commonly when the executing agent
 * disconnected mid-build, leaving no workspace for {@link PagerDutyV2Notifier}
 * to attach to.
 *
 * <p>{@link RunListener#onFinalized} runs on the controller after the build's
 * final state is recorded, regardless of agent state, and does not require a
 * {@code FilePath} or {@code Launcher}. If the publisher already handled the
 * event, it stamps {@link PagerDutyV2HandledAction} on the run and we
 * short-circuit here.</p>
 *
 * <p>Pipeline jobs are not handled here: their {@code pagerDutyV2} step is
 * intentionally opt-in inside the user's script. Pipeline authors who need
 * disconnect-resilient alerting should wrap their build in
 * {@code catchError} / {@code post { failure { ... } }}.</p>
 */
@Extension
public class PagerDutyV2RunListener extends RunListener<Run<?, ?>> {

    private static final Logger LOGGER = Logger.getLogger(PagerDutyV2RunListener.class.getName());

    @Override
    public void onFinalized(@NonNull Run<?, ?> run) {
        if (run.getAction(PagerDutyV2HandledAction.class) != null) {
            return; // PagerDutyV2Notifier#perform already handled (or deliberately skipped) this build
        }

        PagerDutyV2Notifier notifier = findNotifier(run.getParent());
        if (notifier == null) {
            return; // job has no PagerDuty post-build action; nothing to do
        }

        TaskListener listener = new LogTaskListener(LOGGER, Level.INFO);
        try {
            EnvVars env = run.getEnvironment(listener);
            PagerDutyV2Dispatcher.dispatch(run, env, new PagerDutyV2Dispatcher.Config(notifier), listener);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "[pagerduty-v2] Listener-side dispatch failed for " + run.getFullDisplayName(), e);
        }
    }

    private static @CheckForNull PagerDutyV2Notifier findNotifier(@NonNull Job<?, ?> job) {
        if (!(job instanceof AbstractProject)) {
            return null; // freestyle / matrix only; skip pipeline jobs (which use the step)
        }
        AbstractProject<?, ?> ap = (AbstractProject<?, ?>) job;
        DescribableList<Publisher, hudson.model.Descriptor<Publisher>> publishers = ap.getPublishersList();
        for (Publisher p : publishers) {
            if (p instanceof PagerDutyV2Notifier) {
                return (PagerDutyV2Notifier) p;
            }
        }
        return null;
    }
}
