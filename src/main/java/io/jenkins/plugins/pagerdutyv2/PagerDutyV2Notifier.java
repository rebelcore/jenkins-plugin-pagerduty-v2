package io.jenkins.plugins.pagerdutyv2;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.IOException;
import java.util.List;

/**
 * Freestyle/classic post-build notifier that:
 *  - sends TRIGGER on failure-ish
 *  - sends RESOLVE on success after a previous trigger
 *  - reuses trigger payload on resolve by replaying stored JSON
 */
public class PagerDutyV2Notifier extends Notifier implements SimpleBuildStep {

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
        PagerDutyV2Dispatcher.dispatch(run, env, new PagerDutyV2Dispatcher.Config(this), listener);
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
