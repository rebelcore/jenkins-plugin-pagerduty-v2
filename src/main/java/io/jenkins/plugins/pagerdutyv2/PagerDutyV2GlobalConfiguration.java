package io.jenkins.plugins.pagerdutyv2;

import hudson.Extension;
import hudson.util.Secret;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.kohsuke.stapler.DataBoundSetter;

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.QueryParameter;
import hudson.security.ACL;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;



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

    public String getEndpointUrl() {
        return endpointUrl == null || endpointUrl.isBlank() ? DEFAULT_ENDPOINT : endpointUrl.trim();
    }

    @DataBoundSetter
    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
        save();
    }

    public String getRoutingKeyCredentialId() {
        return routingKeyCredentialId;
    }

    @DataBoundSetter
    public void setRoutingKeyCredentialId(String routingKeyCredentialId) {
        this.routingKeyCredentialId = routingKeyCredentialId;
        save();
    }

    public String getSandboxRoutingKeyCredentialId() {
        return sandboxRoutingKeyCredentialId;
    }

    @DataBoundSetter
    public void setSandboxRoutingKeyCredentialId(String sandboxRoutingKeyCredentialId) {
        this.sandboxRoutingKeyCredentialId = sandboxRoutingKeyCredentialId;
        save();
    }

    /** True if a sandbox routing key credential is configured (ID set). */
    public boolean hasSandboxRoutingKeyConfigured() {
        return sandboxRoutingKeyCredentialId != null && !sandboxRoutingKeyCredentialId.isBlank();
    }

    public boolean isDisabled() {
        return disabled;
    }

    @DataBoundSetter
    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
        save();
    }

    public boolean isRequireTags() {
        return requireTags;
    }

    @DataBoundSetter
    public void setRequireTags(boolean requireTags) {
        this.requireTags = requireTags;
        save();
    }

    public @NonNull String getServiceChoicesRaw() {
        return serviceChoices == null ? "" : serviceChoices;
    }

    @DataBoundSetter
    public void setServiceChoicesRaw(String serviceChoicesRaw) {
        this.serviceChoices = serviceChoicesRaw;
        save();
    }

    /**
     * Parsed list of service names from {@link #serviceChoices}.
     * Supports one-per-line or comma-separated input.
     */
    public @NonNull List<String> getServiceChoices() {
        List<String> out = new ArrayList<>();
        String raw = getServiceChoicesRaw();
        for (String line : raw.split("\r?\n")) {
            if (line == null) continue;
            for (String part : line.split(",")) {
                String s = part.trim();
                if (!s.isEmpty() && !out.contains(s)) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** Dropdown helper for Secret Text credentials. */
    public StandardListBoxModel doFillRoutingKeyCredentialIdItems(@QueryParameter String routingKeyCredentialId) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        StandardListBoxModel m = new StandardListBoxModel();
        m.includeEmptyValue();

        List<StringCredentials> creds = CredentialsProvider.lookupCredentials(
                StringCredentials.class,
                Jenkins.get(),
                ACL.SYSTEM,
                Collections.<DomainRequirement>emptyList()
        );

        for (StringCredentials c : creds) {
            String id = c.getId();
            String display = c.getDescription();
            if (display.isBlank()) {
                display = id;
            }
            m.add(display, id);
        }
        return m;
    }

    /** Dropdown helper for Secret Text credentials (sandbox routing key). */
    public StandardListBoxModel doFillSandboxRoutingKeyCredentialIdItems(@QueryParameter String sandboxRoutingKeyCredentialId) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        StandardListBoxModel m = new StandardListBoxModel();
        m.includeEmptyValue();

        List<StringCredentials> creds = CredentialsProvider.lookupCredentials(
                StringCredentials.class,
                Jenkins.get(),
                ACL.SYSTEM,
                Collections.<DomainRequirement>emptyList()
        );

        for (StringCredentials c : creds) {
            String id = c.getId();
            String display = c.getDescription();
            if (display.isBlank()) {
                display = id;
            }
            m.add(display, id);
        }
        return m;
    }

    /** Resolve the routing key secret. */
    public Secret resolveRoutingKey() {
        if (routingKeyCredentialId == null || routingKeyCredentialId.isBlank()) {
            return null;
        }

        List<StringCredentials> creds = CredentialsProvider.lookupCredentials(
                StringCredentials.class,
                Jenkins.get(),
                ACL.SYSTEM,
                Collections.<DomainRequirement>emptyList()
        );

        for (StringCredentials c : creds) {
            if (routingKeyCredentialId.equals(c.getId())) {
                return c.getSecret();
            }
        }
        return null;
    }

    /** Resolve the sandbox routing key secret. */
    public Secret resolveSandboxRoutingKey() {
        if (sandboxRoutingKeyCredentialId == null || sandboxRoutingKeyCredentialId.isBlank()) {
            return null;
        }

        List<StringCredentials> creds = CredentialsProvider.lookupCredentials(
                StringCredentials.class,
                Jenkins.get(),
                ACL.SYSTEM,
                Collections.<DomainRequirement>emptyList()
        );

        for (StringCredentials c : creds) {
            if (sandboxRoutingKeyCredentialId.equals(c.getId())) {
                return c.getSecret();
            }
        }
        return null;
    }

}
