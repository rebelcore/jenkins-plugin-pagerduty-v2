package io.jenkins.plugins.pagerdutyv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pipeline step:
 *   pagerDutyV2(action: 'trigger'|'resolve', severity: 'critical')
 *
 * Payload replay:
 * - action='trigger' stores the payload JSON on the current build (no routing key persisted)
 * - action='resolve' finds the most recent open action and replays the stored payload with
 *   event_action=resolve, injecting the freshly-resolved routing key at send time
 */
public class PagerDutyV2Step extends Step {

    static final Set<String> VALID_ACTIONS = Set.of("trigger", "resolve");
    static final Set<String> VALID_SEVERITIES = Set.of("critical", "error", "warning", "info");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String action = "trigger";
    private String severity = "critical";

    @DataBoundConstructor
    public PagerDutyV2Step() {}

    public @NonNull String getAction() {
        return action;
    }

    @DataBoundSetter
    public void setAction(String action) {
        if (action != null && !action.isBlank()) {
            this.action = action.trim().toLowerCase(Locale.ROOT);
        }
    }

    public @NonNull String getSeverity() {
        return severity;
    }

    @DataBoundSetter
    public void setSeverity(String severity) {
        if (severity != null && !severity.isBlank()) {
            this.severity = severity.trim().toLowerCase(Locale.ROOT);
        }
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(this, context);
    }

    public static class Execution extends SynchronousNonBlockingStepExecution<Void> {
        private static final long serialVersionUID = 1L;
        private final String action;
        private final String severity;

        protected Execution(PagerDutyV2Step step, StepContext context) {
            super(context);
            this.action = step != null ? step.getAction() : "trigger";
            this.severity = step != null ? step.getSeverity() : "critical";
        }

        @Override
        protected Void run() throws Exception {
            Run<?, ?> run = getContext().get(Run.class);
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
            if (!VALID_ACTIONS.contains(action)) {
                throw new IllegalArgumentException("Unsupported action: " + action + " (expected trigger|resolve)");
            }
            if ("trigger".equals(action) && !VALID_SEVERITIES.contains(severity)) {
                throw new IllegalArgumentException("Unsupported severity: " + severity
                        + " (expected critical|error|warning|info)");
            }

            String routingKey = rkSecret.getPlainText();
            PagerDutyV2Client client = new PagerDutyV2Client(cfg.getEndpointUrl());

            if ("trigger".equals(action)) {
                String dedupKey = PayloadBuilder.dedupKey(env);
                Map<String, Object> payload = PayloadBuilder.buildPayload(env, severity);
                Map<String, Object> body = PayloadBuilder.buildBody(routingKey, "trigger", dedupKey, payload);

                client.postEvent(body);

                String payloadJson = MAPPER.writeValueAsString(payload);
                run.addAction(new PagerDutyV2RunAction(dedupKey, payloadJson));
                run.save();

                listener.getLogger().println("[pagerduty-v2] Trigger sent (dedup_key=" + dedupKey + ")");
                return null;
            }

            // resolve
            PagerDutyV2RunAction openAction = findMostRecentOpenAction(run);
            if (openAction == null) {
                listener.getLogger().println("[pagerduty-v2] No open incident found; nothing to resolve.");
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> storedPayload = MAPPER.readValue(openAction.getPayloadJson(), Map.class);
            Map<String, Object> resolveBody = PayloadBuilder.buildBody(
                    routingKey, "resolve", openAction.getDedupKey(), storedPayload);

            client.postEvent(resolveBody);

            openAction.markResolved();
            Run<?, ?> owner = openAction.getOwner();
            if (owner != null) {
                owner.save();
            } else {
                run.save();
            }

            listener.getLogger().println("[pagerduty-v2] Resolve sent (dedup_key=" + openAction.getDedupKey() + ")");
            return null;
        }

        private @CheckForNull PagerDutyV2RunAction findMostRecentOpenAction(@NonNull Run<?, ?> run) {
            for (Run<?, ?> r = run; r != null; r = r.getPreviousBuild()) {
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
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class, EnvVars.class);
        }

        public ListBoxModel doFillActionItems() {
            ListBoxModel m = new ListBoxModel();
            for (String a : VALID_ACTIONS) {
                m.add(a, a);
            }
            return m;
        }

        public ListBoxModel doFillSeverityItems() {
            ListBoxModel m = new ListBoxModel();
            for (String s : VALID_SEVERITIES) {
                m.add(s, s);
            }
            return m;
        }

        public FormValidation doCheckAction(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.error("Action is required.");
            }
            if (!VALID_ACTIONS.contains(value.trim().toLowerCase(Locale.ROOT))) {
                return FormValidation.error("Action must be one of: trigger, resolve.");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckSeverity(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            if (!VALID_SEVERITIES.contains(value.trim().toLowerCase(Locale.ROOT))) {
                return FormValidation.error("Severity must be one of: critical, error, warning, info.");
            }
            return FormValidation.ok();
        }
    }
}
