package io.jenkins.plugins.pagerdutyv2;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.Secret;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Global configuration for PagerDuty Events API v2.
 */
@Extension
public final class PagerDutyV2GlobalConfiguration extends GlobalConfiguration {

    /** Default PagerDuty Events API v2 endpoint. */
    public static final String DEFAULT_ENDPOINT = "https://events.pagerduty.com/v2/enqueue";

    private String endpointUrl = DEFAULT_ENDPOINT;

    /** Credential ID for Secret Text (routing key). */
    private String routingKeyCredentialId;

    /** Optional credential ID for Secret Text (sandbox routing key). */
    private String sandboxRoutingKeyCredentialId;

    /** When true, plugin will not send any events to PagerDuty. */
    private boolean disabled = false;

    /** If true, job configuration must include Tags (enforced on save). */
    private boolean requireTags = false;

    /** Optional list of allowed services (one per line or comma-separated). If set, job config shows a dropdown. */
    private String serviceChoices = "";

    public PagerDutyV2GlobalConfiguration() {
        load();
    }

    public static PagerDutyV2GlobalConfiguration get() {
        return GlobalConfiguration.all().get(PagerDutyV2GlobalConfiguration.class);
    }

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) {
        // Reset booleans because unchecked checkboxes are absent from JSON.
        this.disabled = false;
        this.requireTags = false;
        req.bindJSON(this, json);
        save();
        return true;
    }

    public String getEndpointUrl() {
        return endpointUrl == null || endpointUrl.isBlank() ? DEFAULT_ENDPOINT : endpointUrl.trim();
    }

    @DataBoundSetter
    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    public String getRoutingKeyCredentialId() {
        return routingKeyCredentialId;
    }

    @DataBoundSetter
    public void setRoutingKeyCredentialId(String routingKeyCredentialId) {
        this.routingKeyCredentialId = routingKeyCredentialId;
    }

    public String getSandboxRoutingKeyCredentialId() {
        return sandboxRoutingKeyCredentialId;
    }

    @DataBoundSetter
    public void setSandboxRoutingKeyCredentialId(String sandboxRoutingKeyCredentialId) {
        this.sandboxRoutingKeyCredentialId = sandboxRoutingKeyCredentialId;
    }

    public boolean hasSandboxRoutingKeyConfigured() {
        return sandboxRoutingKeyCredentialId != null && !sandboxRoutingKeyCredentialId.isBlank();
    }

    public boolean isDisabled() {
        return disabled;
    }

    @DataBoundSetter
    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    public boolean isRequireTags() {
        return requireTags;
    }

    @DataBoundSetter
    public void setRequireTags(boolean requireTags) {
        this.requireTags = requireTags;
    }

    public @NonNull String getServiceChoicesRaw() {
        return serviceChoices == null ? "" : serviceChoices;
    }

    @DataBoundSetter
    public void setServiceChoicesRaw(String serviceChoicesRaw) {
        this.serviceChoices = serviceChoicesRaw;
    }

    /**
     * Parsed list of service names from {@link #serviceChoices}.
     * Supports one-per-line or comma-separated input.
     */
    public @NonNull List<String> getServiceChoices() {
        List<String> out = new ArrayList<>();
        for (String line : getServiceChoicesRaw().split("\r?\n")) {
            for (String part : line.split(",")) {
                String s = part.trim();
                if (!s.isEmpty() && !out.contains(s)) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    @POST
    public StandardListBoxModel doFillRoutingKeyCredentialIdItems(@QueryParameter String routingKeyCredentialId) {
        return credentialsListBoxModel(routingKeyCredentialId);
    }

    @POST
    public StandardListBoxModel doFillSandboxRoutingKeyCredentialIdItems(
            @QueryParameter String sandboxRoutingKeyCredentialId) {
        return credentialsListBoxModel(sandboxRoutingKeyCredentialId);
    }

    private StandardListBoxModel credentialsListBoxModel(@CheckForNull String currentValue) {
        StandardListBoxModel m = new StandardListBoxModel();
        String safeCurrent = currentValue == null ? "" : currentValue;
        Jenkins j = Jenkins.get();
        if (!j.hasPermission(Jenkins.ADMINISTER)) {
            m.includeCurrentValue(safeCurrent);
            return m;
        }
        m.includeEmptyValue();
        m.includeMatchingAs(
                ACL.SYSTEM2,
                j,
                StringCredentials.class,
                Collections.<DomainRequirement>emptyList(),
                CredentialsMatchers.always());
        m.includeCurrentValue(safeCurrent);
        return m;
    }

    @POST
    public FormValidation doCheckEndpointUrl(@QueryParameter String value) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (value == null || value.isBlank()) {
            return FormValidation.ok(); // empty falls back to default
        }
        try {
            URI u = URI.create(value.trim());
            String scheme = u.getScheme();
            if (scheme == null || (!scheme.equals("https") && !scheme.equals("http"))) {
                return FormValidation.error("Endpoint URL must start with http:// or https://");
            }
            if (u.getHost() == null || u.getHost().isBlank()) {
                return FormValidation.error("Endpoint URL must include a host.");
            }
            if ("http".equals(scheme)) {
                return FormValidation.warning("Plain HTTP is insecure; prefer https://.");
            }
            return FormValidation.ok();
        } catch (IllegalArgumentException e) {
            return FormValidation.error("Not a valid URL: " + e.getMessage());
        }
    }

    /** Resolve the routing key secret. */
    public @CheckForNull Secret resolveRoutingKey() {
        return resolveSecret(routingKeyCredentialId);
    }

    /** Resolve the sandbox routing key secret. */
    public @CheckForNull Secret resolveSandboxRoutingKey() {
        return resolveSecret(sandboxRoutingKeyCredentialId);
    }

    private static @CheckForNull Secret resolveSecret(@CheckForNull String credentialId) {
        if (credentialId == null || credentialId.isBlank()) {
            return null;
        }
        List<StringCredentials> creds = CredentialsProvider.lookupCredentialsInItemGroup(
                StringCredentials.class,
                Jenkins.get(),
                ACL.SYSTEM2,
                Collections.<DomainRequirement>emptyList());
        for (StringCredentials c : creds) {
            if (credentialId.equals(c.getId())) {
                return c.getSecret();
            }
        }
        return null;
    }
}
