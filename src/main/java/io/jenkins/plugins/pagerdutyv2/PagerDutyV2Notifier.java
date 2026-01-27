package io.jenkins.plugins.pagerdutyv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.EnvVars;
import hudson.util.ListBoxModel;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.Secret;
import jenkins.tasks.SimpleBuildStep;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.DataBoundSetter;
import org.jenkinsci.Symbol;

import java.io.IOException;
import java.util.Map;
import java.util.List;

import hudson.model.Item;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.interceptor.RequirePOST;

import hudson.model.Descriptor;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Freestyle/classic post-build notifier that:
 *  - sends TRIGGER on failure-ish
 *  - sends RESOLVE on success after a previous trigger
 *  - reuses trigger payload on resolve by replaying stored JSON
 */
public class PagerDutyV2Notifier extends Notifier implements SimpleBuildStep {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String severityOnFailure = "error";

    // Job-level options
    private String service = "";
    private String tags = "";

    /** If true, use the sandbox routing key configured in global settings (when defined). */
    private boolean sandboxMode = false;
    private int consecutiveBuildsBeforeTrigger = 1;
    private boolean triggerOnSuccess = false;
    private boolean triggerOnFailure = true;
    private boolean triggerOnUnstable = false;
    private boolean triggerOnAbort = false;
    private boolean triggerOnNotBuilt = false;
    private boolean resolveOnBackToNormal = true;

    // Optional: include a tail of the console log in trigger events
    private boolean includeConsoleLogTail = false;
    private int consoleLogTailLines = 200;

    // Optional: override the PagerDuty event summary
    private boolean useCustomSummary = false;
    private String customSummary = "";

    @DataBoundConstructor
    public PagerDutyV2Notifier() {}

    public @NonNull String getSeverityOnFailure() {
        return severityOnFailure;
    }

    @DataBoundSetter
    public void setSeverityOnFailure(String severityOnFailure) {
        if (severityOnFailure != null && !severityOnFailure.isBlank()) {
            this.severityOnFailure = severityOnFailure.trim();
        }
    }

    public @NonNull String getService() {
        return service == null ? "" : service;
    }

    @DataBoundSetter
    public void setService(String service) {
        this.service = service != null ? service.trim() : "";
    }

    public @NonNull String getTags() {
        return tags == null ? "" : tags;
    }

    public boolean isSandboxMode() {
        return sandboxMode;
    }

    @DataBoundSetter
    public void setSandboxMode(boolean sandboxMode) {
        this.sandboxMode = sandboxMode;
    }

    @DataBoundSetter
    public void setTags(String tags) {
        this.tags = tags != null ? tags.trim() : "";
    }

    public int getConsecutiveBuildsBeforeTrigger() {
        return consecutiveBuildsBeforeTrigger;
    }

    @DataBoundSetter
    public void setConsecutiveBuildsBeforeTrigger(int consecutiveBuildsBeforeTrigger) {
        this.consecutiveBuildsBeforeTrigger = Math.max(1, consecutiveBuildsBeforeTrigger);
    }

    public boolean isTriggerOnSuccess() {
        return triggerOnSuccess;
    }

    @DataBoundSetter
    public void setTriggerOnSuccess(boolean triggerOnSuccess) {
        this.triggerOnSuccess = triggerOnSuccess;
    }

    public boolean isTriggerOnFailure() {
        return triggerOnFailure;
    }

    @DataBoundSetter
    public void setTriggerOnFailure(boolean triggerOnFailure) {
        this.triggerOnFailure = triggerOnFailure;
    }

    public boolean isTriggerOnUnstable() {
        return triggerOnUnstable;
    }

    @DataBoundSetter
    public void setTriggerOnUnstable(boolean triggerOnUnstable) {
        this.triggerOnUnstable = triggerOnUnstable;
    }

    public boolean isTriggerOnAbort() {
        return triggerOnAbort;
    }

    @DataBoundSetter
    public void setTriggerOnAbort(boolean triggerOnAbort) {
        this.triggerOnAbort = triggerOnAbort;
    }

    public boolean isTriggerOnNotBuilt() {
        return triggerOnNotBuilt;
    }

    @DataBoundSetter
    public void setTriggerOnNotBuilt(boolean triggerOnNotBuilt) {
        this.triggerOnNotBuilt = triggerOnNotBuilt;
    }

    public boolean isResolveOnBackToNormal() {
        return resolveOnBackToNormal;
    }

    public boolean isIncludeConsoleLogTail() {
        return includeConsoleLogTail;
    }

    @DataBoundSetter
    public void setIncludeConsoleLogTail(boolean includeConsoleLogTail) {
        this.includeConsoleLogTail = includeConsoleLogTail;
    }

    public int getConsoleLogTailLines() {
        return consoleLogTailLines;
    }

    @DataBoundSetter
    public void setConsoleLogTailLines(int consoleLogTailLines) {
        this.consoleLogTailLines = Math.max(1, consoleLogTailLines);
    }

    public boolean isUseCustomSummary() {
        return useCustomSummary;
    }

    @DataBoundSetter
    public void setUseCustomSummary(boolean useCustomSummary) {
        this.useCustomSummary = useCustomSummary;
    }

    public @NonNull String getCustomSummary() {
        return customSummary == null ? "" : customSummary;
    }

    @DataBoundSetter
    public void setCustomSummary(String customSummary) {
        this.customSummary = customSummary;
    }

	@DataBoundSetter
    public void setResolveOnBackToNormal(boolean resolveOnBackToNormal) {
        this.resolveOnBackToNormal = resolveOnBackToNormal;
    }

    @Override
    public void perform(@NonNull Run<?, ?> run,
                        @NonNull hudson.FilePath workspace,
                        @NonNull EnvVars env,
                        @NonNull Launcher launcher,
                        @NonNull TaskListener listener) throws InterruptedException, IOException {

        Result result = run.getResult();
        if (result == null) {
            return;
        }

        PagerDutyV2GlobalConfiguration cfg = PagerDutyV2GlobalConfiguration.get();
        if (cfg.isDisabled()) {
            listener.getLogger().println("[pagerduty-v2] PagerDuty is disabled in system configuration; skipping.");
            return;
        }

        if (getService().isBlank()) {
            listener.getLogger().println("[pagerduty-v2] Service is required; skipping PagerDuty notification.");
            return;
        }

        Secret rkSecret = null;
        if (isSandboxMode()) {
            Secret sandbox = cfg.resolveSandboxRoutingKey();
            if (sandbox != null) {
                rkSecret = sandbox;
                listener.getLogger().println("[pagerduty-v2] Sandbox mode enabled; using sandbox routing key.");
            } else {
                listener.getLogger().println("[pagerduty-v2] Sandbox mode enabled but no sandbox routing key configured; falling back to primary routing key.");
            }
        }
        if (rkSecret == null) {
            rkSecret = cfg.resolveRoutingKey();
        }
        if (rkSecret == null) {
            listener.getLogger().println("[pagerduty-v2] No routing key credential configured; skipping.");
            return;
        }

        String routingKey = rkSecret.getPlainText();
        String endpoint = cfg.getEndpointUrl();

        PagerDutyV2Client client = new PagerDutyV2Client(endpoint);

        boolean shouldTrigger = shouldTriggerFor(result);

        PagerDutyV2RunAction openAction = findMostRecentOpenAction(run);

        if (shouldTrigger) {
            int streak = consecutiveTriggerStreak(run);
            int threshold = Math.max(1, consecutiveBuildsBeforeTrigger);

            if (streak < threshold) {
                listener.getLogger().println("[pagerduty-v2] Trigger condition met but streak "
                        + streak + "/" + threshold + " not reached; not triggering yet.");
                return;
            }

            if (openAction == null) {
                String dedupKey = PayloadBuilder.dedupKey(env);
                Map<String, Object> payload = PayloadBuilder.buildPayload(env, severityOnFailure);
                if (isUseCustomSummary()) {
                    String s = getCustomSummary().trim();
                    if (!s.isEmpty()) {
                        payload.put("summary", s);
                    }
                }

                payload.put("component", getService().trim());

                Object cd = payload.get("custom_details");
                if (cd instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> details = (Map<String, Object>) cd;

                    if (!getTags().trim().isEmpty()) {
                        details.put("tags", getTags().trim());
                    }
                    details.put("service", getService().trim());
                    details.put("result", result.toString());
                    details.put("consecutive_streak", streak);

                    if (isIncludeConsoleLogTail() && !Result.SUCCESS.equals(result)) {
                        String logTail = getConsoleLogTail(run, getConsoleLogTailLines(), 4000);
                        if (!logTail.isEmpty()) {
                            details.put("console_log_tail", logTail);
                        }
                    }
                }

                Map<String, Object> body = PayloadBuilder.buildBody(routingKey, "trigger", dedupKey, payload);

                String json = MAPPER.writeValueAsString(body);
                client.postEvent(body);

                run.addAction(new PagerDutyV2RunAction(dedupKey, json));
                run.save();

                listener.getLogger().println("[pagerduty-v2] Trigger sent (dedup_key=" + dedupKey + ")");
            } else {
                listener.getLogger().println("[pagerduty-v2] Open incident already exists (dedup_key="
                        + openAction.getDedupKey() + "); not triggering again.");
            }
            return;
        }

        if (resolveOnBackToNormal && openAction != null && result.equals(Result.SUCCESS)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> triggerBody = MAPPER.readValue(openAction.getTriggerBodyJson(), Map.class);
            triggerBody.put("event_action", "resolve");
            client.postEvent(triggerBody);
            openAction.markResolved();
            Run<?, ?> owner = openAction.getOwner();
            if (owner != null) {
                owner.save();
            } else {
                run.save();
            }
            listener.getLogger().println("[pagerduty-v2] Resolve sent (dedup_key=" + openAction.getDedupKey() + ")");
        }
    }

    /**
     * Best-effort console log tail capture. Returns up to {@code maxChars} characters from the end of the log.
     * This helps get "what failed" into PagerDuty without exceeding event size limits.
     */
    private static @NonNull String getConsoleLogTail(@NonNull Run<?, ?> run, int maxLines, int maxChars) {
        if (maxLines <= 0 || maxChars <= 0) {
            return "";
        }
        try {
            List<String> lines = run.getLog(Math.max(1, maxLines));
            String joined = String.join("\n", lines);
            if (joined.length() <= maxChars) {
                return joined;
            }
            return joined.substring(joined.length() - maxChars);
        } catch (IOException e) {
            return "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private boolean shouldTriggerFor(@NonNull Result result) {
        if (result.equals(Result.SUCCESS)) {
            return triggerOnSuccess;
        }
        if (result.equals(Result.FAILURE)) {
            return triggerOnFailure;
        }
        if (result.equals(Result.UNSTABLE)) {
            return triggerOnUnstable;
        }
        if (result.equals(Result.ABORTED)) {
            return triggerOnAbort;
        }
        if (result.equals(Result.NOT_BUILT)) {
            return triggerOnNotBuilt;
        }
        return false;
    }

    private int consecutiveTriggerStreak(@NonNull Run<?, ?> run) {
        int count = 0;
        for (Run<?, ?> r = run; r != null; r = r.getPreviousBuild()) {
            Result res = r.getResult();
            if (res == null) {
                break;
            }
            if (shouldTriggerFor(res)) {
                count++;
            } else {
                break;
            }
        }
        return count;
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

    @Extension
    @Symbol("pagerDutyV2Notifier")
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        /** Whether Tags are required in job configuration (controlled by global system config). */
        public boolean isTagsRequired() {
            return PagerDutyV2GlobalConfiguration.get().isRequireTags();
        }

        /**
         * Server-side validation on Save.
         * This prevents saving the job when the placeholder ("") is selected.
         */
        @Override
        public PagerDutyV2Notifier newInstance(@NonNull StaplerRequest2 req, @NonNull JSONObject formData) throws Descriptor.FormException {
            Object includeObj = formData.get("includeConsoleLogTail");
            if (includeObj instanceof JSONObject) {
                JSONObject o = (JSONObject) includeObj;
                formData.put("includeConsoleLogTail", true);
                if (o.has("consoleLogTailLines")) {
                    formData.put("consoleLogTailLines", o.get("consoleLogTailLines"));
                }
            }

            Object summaryObj = formData.get("useCustomSummary");
            if (summaryObj instanceof JSONObject) {
                JSONObject o = (JSONObject) summaryObj;
                formData.put("useCustomSummary", true);
                if (o.has("customSummary")) {
                    formData.put("customSummary", o.get("customSummary"));
                }
            }

            PagerDutyV2Notifier n = req.bindJSON(PagerDutyV2Notifier.class, formData);

            if (PagerDutyV2GlobalConfiguration.get().isRequireTags() && n.getTags().trim().isEmpty()) {
                throw new Descriptor.FormException("Tags are required.", "tags");
            }

            String svc = n.getService().trim();
            List<String> choices = PagerDutyV2GlobalConfiguration.get().getServiceChoices();

            if (!choices.isEmpty()) {
                if (svc.isEmpty()) {
                    throw new Descriptor.FormException("Please select a service.", "service");
                }
                if (!choices.contains(svc)) {
                    throw new Descriptor.FormException(
                            "Service must be one of the values defined in System Configuration.",
                            "service");
                }
            } else {
                if (svc.isEmpty()) {
                    throw new Descriptor.FormException("Service is required.", "service");
                }
            }

            return n;
        }

        public ListBoxModel doFillSeverityOnFailureItems(@QueryParameter String severityOnFailure) {
            String current = (severityOnFailure == null || severityOnFailure.isBlank())
                    ? "error"
                    : severityOnFailure.trim();

            ListBoxModel m = new ListBoxModel();
            m.add(new ListBoxModel.Option("critical", "critical", "critical".equals(current)));
            m.add(new ListBoxModel.Option("error", "error", "error".equals(current)));
            m.add(new ListBoxModel.Option("warning", "warning", "warning".equals(current)));
            m.add(new ListBoxModel.Option("info", "info", "info".equals(current)));

            return m;
        }

        /** Whether global service choices are configured (controls textbox vs dropdown in Jelly). */
        public boolean hasServiceChoices() {
            return !PagerDutyV2GlobalConfiguration.get().getServiceChoices().isEmpty();
        }

        /** Whether a sandbox routing key is configured globally (controls visibility of sandbox checkbox in Jelly). */
        public boolean hasSandboxRoutingKeyConfigured() {
            return PagerDutyV2GlobalConfiguration.get().hasSandboxRoutingKeyConfigured();
        }

        /** Service dropdown populated from global configuration when defined. */
        public ListBoxModel doFillServiceItems(@QueryParameter String service) {
            List<String> choices = PagerDutyV2GlobalConfiguration.get().getServiceChoices();
            ListBoxModel m = new ListBoxModel();

            m.add("-- select a service --", "");

            for (String c : choices) {
                m.add(c, c);
            }

            if (service != null && !service.isBlank()) {
                boolean found = false;
                for (ListBoxModel.Option o : m) {
                    if (service.equals(o.value)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    ListBoxModel m2 = new ListBoxModel();
                    m2.add(service + " (current)", service);
                    m2.addAll(m);
                    m = m2;
                }
            }

            return m;
        }

        @RequirePOST
        public FormValidation doCheckService(@AncestorInPath Item item, @QueryParameter String value) {
            checkConfigurePermission(item);

            List<String> choices = PagerDutyV2GlobalConfiguration.get().getServiceChoices();

            if (value == null || value.trim().isEmpty()) {
                if (!choices.isEmpty()) {
                    return FormValidation.error("Please select a service.");
                }
                return FormValidation.error("Service is required.");
            }

            String v = value.trim();
            if (!choices.isEmpty() && !choices.contains(v)) {
                return FormValidation.error("Service must be one of the values defined in System Configuration.");
            }

            return FormValidation.ok();
        }

        @RequirePOST
        public FormValidation doCheckTags(@AncestorInPath Item item, @QueryParameter String value) {
            checkConfigurePermission(item);
            if (PagerDutyV2GlobalConfiguration.get().isRequireTags()) {
                if (value == null || value.trim().isEmpty()) {
                    return FormValidation.error("Tags are required.");
                }
            }
            return FormValidation.ok();
        }

        private static void checkConfigurePermission(@CheckForNull Item item) {
            if (item != null) {
                item.checkPermission(Item.CONFIGURE);
            } else {
                Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            }
        }

        @Override
        public boolean isApplicable(Class jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "PagerDuty v2";
        }
    }
}
