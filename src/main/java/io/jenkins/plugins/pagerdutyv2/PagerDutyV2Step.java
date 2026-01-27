package io.jenkins.plugins.pagerdutyv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.Secret;
import org.jenkinsci.plugins.workflow.steps.*;
import org.jenkinsci.Symbol;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.Map;

/**
 * Pipeline step:
 *   pagerDutyV2(action: 'trigger'|'resolve', severity: 'critical')
 *
 * Payload replay:
 * - action='trigger' stores trigger JSON on the current build
 * - action='resolve' finds the most recent open action and replays payload with event_action=resolve
 */
public class PagerDutyV2Step extends Step {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String action = "trigger"; // trigger|resolve
    private String severity = "critical";

    @DataBoundConstructor
    public PagerDutyV2Step() {}

    public @NonNull String getAction() {
        return action;
    }

    @DataBoundSetter
    public void setAction(String action) {
        if (action != null && !action.isBlank()) {
            this.action = action.trim();
        }
    }

    public @NonNull String getSeverity() {
        return severity;
    }

    @DataBoundSetter
    public void setSeverity(String severity) {
        if (severity != null && !severity.isBlank()) {
            this.severity = severity.trim();
        }
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(this, context);
    }

    public static class Execution extends SynchronousNonBlockingStepExecution<Void> {
        private static final long serialVersionUID = 1L;
        private String action;
        private String severity;
        protected Execution(PagerDutyV2Step step, StepContext context) {
            super(context);
            this.action = step != null ? step.getAction() : "trigger";
            this.severity = step != null ? step.getSeverity() : "critical";
        }

        @Override
        protected Void run() throws Exception {
            Run<?,?> run = getContext().get(Run.class);
            TaskListener listener = getContext().get(TaskListener.class);
            EnvVars env = getContext().get(EnvVars.class);

            if (run == null || listener == null || env == null) {
                throw new IllegalStateException("Missing required Jenkins context (run/listener/env).");
            }

            PagerDutyV2GlobalConfiguration cfg = PagerDutyV2GlobalConfiguration.get();
            if (cfg.isDisabled()) {
                listener.getLogger().println("[pagerduty-v2] PagerDuty is disabled in system configuration; skipping.");
                return null;
            }
            Secret rkSecret = cfg.resolveRoutingKey();
            if (rkSecret == null) {
                listener.getLogger().println("[pagerduty-v2] No routing key credential configured; skipping.");
                return null;
            }

            String routingKey = rkSecret.getPlainText();
            String endpoint = cfg.getEndpointUrl();
            PagerDutyV2Client client = new PagerDutyV2Client(endpoint);

            String action = this.action;

            if ("trigger".equalsIgnoreCase(action)) {
                String dedupKey = PayloadBuilder.dedupKey(env);
                Map<String,Object> payload = PayloadBuilder.buildPayload(env, this.severity);
                Map<String,Object> body = PayloadBuilder.buildBody(routingKey, "trigger", dedupKey, payload);

                String json = MAPPER.writeValueAsString(body);
                client.postEvent(body);

                run.addAction(new PagerDutyV2RunAction(dedupKey, json));
                run.save();

                listener.getLogger().println("[pagerduty-v2] Trigger sent (dedup_key=" + dedupKey + ")");
                return null;
            }

            if ("resolve".equalsIgnoreCase(action)) {
                PagerDutyV2RunAction openAction = findMostRecentOpenAction(run);
                if (openAction == null) {
                    listener.getLogger().println("[pagerduty-v2] No open incident found; nothing to resolve.");
                    return null;
                }

                Map<String,Object> triggerBody = MAPPER.readValue(openAction.getTriggerBodyJson(), Map.class);
                triggerBody.put("event_action", "resolve");
                client.postEvent(triggerBody);

                openAction.markResolved();
                run.save();

                listener.getLogger().println("[pagerduty-v2] Resolve sent (dedup_key=" + openAction.getDedupKey() + ")");
                return null;
            }

            throw new IllegalArgumentException("Unsupported action: " + action + " (expected trigger|resolve)");
        }

        private @CheckForNull PagerDutyV2RunAction findMostRecentOpenAction(@NonNull Run<?,?> run) {
            for (Run<?,?> r = run; r != null; r = r.getPreviousBuild()) {
                PagerDutyV2RunAction a = r.getAction(PagerDutyV2RunAction.class);
                if (a != null && a.isOpen()) {
                    return a;
                }
            }
            return null;
        }
    }

    @Extension(optional = true)
    @Symbol("pagerDutyV2")
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "pagerDutyV2";
        }

        @Override
        public String getDisplayName() {
            return "Send PagerDuty Events API v2 (trigger/resolve)";
        }

        @Override
        public java.util.Set<? extends Class<?>> getRequiredContext() {
            return java.util.Set.of(Run.class, TaskListener.class, EnvVars.class);
        }
    }
}
