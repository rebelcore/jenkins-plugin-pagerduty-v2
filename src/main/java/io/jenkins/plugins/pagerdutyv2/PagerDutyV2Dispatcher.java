// Copyright 2010 Rebel Media
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.jenkins.plugins.pagerdutyv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.Secret;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared trigger/resolve decision + send logic, used by both the post-build
 * publisher path ({@link PagerDutyV2Notifier#perform}) and the
 * controller-side {@link PagerDutyV2RunListener} fallback that handles cases
 * where publishers can't run (e.g. agent disconnected, workspace gone).
 *
 * <p>Pure function of {@link Run} state — does not require {@code FilePath},
 * {@code Launcher}, or any agent-side resource.</p>
 */
final class PagerDutyV2Dispatcher {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int FAILURE_SIGNATURE_SCAN_LINES = 300;
    private static final int FAILURE_SIGNATURE_SCAN_MAX_CHARS = 12000;

    private PagerDutyV2Dispatcher() {}

    /** Configuration snapshot taken from a {@link PagerDutyV2Notifier} (or built ad-hoc by the listener). */
    static final class Config {
        final String service;
        final String tags;
        final String severityOnFailure;
        final boolean sandboxMode;
        final int consecutiveBuildsBeforeTrigger;
        final boolean triggerOnSuccess;
        final boolean triggerOnFailure;
        final boolean triggerOnUnstable;
        final boolean triggerOnAbort;
        final boolean triggerOnNotBuilt;
        final boolean resolveOnBackToNormal;
        final boolean includeConsoleLogTail;
        final int consoleLogTailLines;
        final boolean useCustomSummary;
        final String customSummary;

        Config(PagerDutyV2Notifier n) {
            this.service = n.getService();
            this.tags = n.getTags();
            this.severityOnFailure = n.getSeverityOnFailure();
            this.sandboxMode = n.isSandboxMode();
            this.consecutiveBuildsBeforeTrigger = n.getConsecutiveBuildsBeforeTrigger();
            this.triggerOnSuccess = n.isTriggerOnSuccess();
            this.triggerOnFailure = n.isTriggerOnFailure();
            this.triggerOnUnstable = n.isTriggerOnUnstable();
            this.triggerOnAbort = n.isTriggerOnAbort();
            this.triggerOnNotBuilt = n.isTriggerOnNotBuilt();
            this.resolveOnBackToNormal = n.isResolveOnBackToNormal();
            this.includeConsoleLogTail = n.isIncludeConsoleLogTail();
            this.consoleLogTailLines = n.getConsoleLogTailLines();
            this.useCustomSummary = n.isUseCustomSummary();
            this.customSummary = n.getCustomSummary();
        }
    }

    /**
     * Run the trigger/resolve decision for {@code run} and dispatch any
     * resulting event to PagerDuty. Marks the run with
     * {@link PagerDutyV2HandledAction} whatever the outcome, including
     * "skipped" cases and a send that fails, so the
     * {@link PagerDutyV2RunListener} fallback does not dispatch it again.
     */
    static void dispatch(
            @NonNull Run<?, ?> run, @NonNull EnvVars env, @NonNull Config c, @NonNull TaskListener listener)
            throws IOException {
        Result result = run.getResult();
        if (result == null) {
            return; // build still in progress; nothing to do
        }

        PagerDutyV2GlobalConfiguration cfg = PagerDutyV2GlobalConfiguration.get();
        if (cfg.isDisabled()) {
            listener.getLogger().println("[pagerduty-v2] PagerDuty is disabled in system configuration; skipping.");
            markHandled(run, "disabled");
            return;
        }

        if (c.service == null || c.service.isBlank()) {
            listener.getLogger().println("[pagerduty-v2] Service is required; skipping PagerDuty notification.");
            markHandled(run, "no-service");
            return;
        }

        PagerDutyV2Client client = new PagerDutyV2Client(cfg.getEndpointUrl());

        boolean shouldTrigger = shouldTriggerFor(c, result);
        PagerDutyV2RunAction openAction = findMostRecentOpenAction(run);

        if (shouldTrigger) {
            int streak = consecutiveTriggerStreak(c, run);
            if (streak < c.consecutiveBuildsBeforeTrigger) {
                listener.getLogger()
                        .println("[pagerduty-v2] Trigger condition met but streak " + streak + "/"
                                + c.consecutiveBuildsBeforeTrigger + " not reached; not triggering yet.");
                markHandled(run, "streak-not-reached");
                return;
            }

            if (openAction != null) {
                listener.getLogger()
                        .println("[pagerduty-v2] Open incident already exists (dedup_key=" + openAction.getDedupKey()
                                + "); not triggering again.");
                markHandled(run, "open-incident-exists");
                return;
            }

            // The job's sandbox setting picks the integration for a new incident. The choice is
            // recorded on the build, because the resolve has to go to the same integration.
            boolean sandbox = false;
            Secret rkSecret = null;
            if (c.sandboxMode) {
                Secret sandboxKey = cfg.resolveSandboxRoutingKey();
                if (sandboxKey != null) {
                    rkSecret = sandboxKey;
                    sandbox = true;
                    listener.getLogger().println("[pagerduty-v2] Sandbox mode enabled; using sandbox routing key.");
                } else {
                    listener.getLogger()
                            .println("[pagerduty-v2] Sandbox mode enabled but no sandbox routing key configured; "
                                    + "falling back to primary routing key.");
                }
            }
            if (rkSecret == null) {
                rkSecret = cfg.resolveRoutingKey();
            }
            if (rkSecret == null) {
                listener.getLogger().println("[pagerduty-v2] No routing key credential configured; skipping.");
                markHandled(run, "no-routing-key");
                return;
            }

            String dedupKey = PayloadBuilder.dedupKey(env);
            Map<String, Object> payload = PayloadBuilder.buildPayload(env, c.severityOnFailure);

            if (c.useCustomSummary) {
                String s = c.customSummary == null ? "" : c.customSummary.trim();
                if (!s.isEmpty()) {
                    payload.put("summary", s);
                }
            }

            boolean executorDisconnected = !Result.SUCCESS.equals(result) && isExecutorDisconnected(run);
            if (executorDisconnected && !c.useCustomSummary) {
                Object summary = payload.get("summary");
                if (summary instanceof String s && !s.toLowerCase(Locale.ROOT).contains("executor disconnected")) {
                    payload.put("summary", s + " (executor disconnected)");
                }
            }

            payload.put("component", c.service.trim());

            Object cd = payload.get("custom_details");
            if (cd instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> details = (Map<String, Object>) cd;

                if (c.tags != null && !c.tags.trim().isEmpty()) {
                    details.put("tags", c.tags.trim());
                }
                details.put("service", c.service.trim());
                details.put("result", result.toString());
                details.put("consecutive_streak", streak);
                if (executorDisconnected) {
                    details.put("executor_disconnected", true);
                    details.put("failure_reason", "executor_disconnected");
                }

                if (c.includeConsoleLogTail && !Result.SUCCESS.equals(result)) {
                    String logTail = getConsoleLogTail(run, c.consoleLogTailLines, 4000);
                    if (!logTail.isEmpty()) {
                        details.put("console_log_tail", logTail);
                    }
                }
            }

            Map<String, Object> body = PayloadBuilder.buildBody(rkSecret.getPlainText(), "trigger", dedupKey, payload);
            // Marked before sending: when the send fails, the fallback listener must not dispatch,
            // and retry, the same event a second time.
            markHandled(run, "trigger");
            client.postEvent(body);

            String payloadJson = MAPPER.writeValueAsString(payload);
            PagerDutyV2RunAction action = new PagerDutyV2RunAction(dedupKey, payloadJson, sandbox);
            run.addAction(action);
            action.keepOwnerWhileOpen();
            run.save();

            listener.getLogger().println("[pagerduty-v2] Trigger sent (dedup_key=" + dedupKey + ")");
            return;
        }

        if (c.resolveOnBackToNormal && openAction != null && Result.SUCCESS.equals(result)) {
            Secret rkSecret = routingKeyThatOpened(openAction, cfg);
            if (rkSecret == null) {
                logResolveKeyMissing(openAction, listener);
                markHandled(run, "no-routing-key-for-resolve");
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> storedPayload = MAPPER.readValue(openAction.getPayloadJson(), Map.class);
            Map<String, Object> resolveBody = PayloadBuilder.buildBody(
                    rkSecret.getPlainText(), "resolve", openAction.getDedupKey(), storedPayload);
            markHandled(run, "resolve");
            client.postEvent(resolveBody);
            openAction.markResolved();
            openAction.stopKeepingOwner();
            Run<?, ?> owner = openAction.getOwner();
            if (owner != null) {
                owner.save();
            } else {
                run.save();
            }
            listener.getLogger().println("[pagerduty-v2] Resolve sent (dedup_key=" + openAction.getDedupKey() + ")");
            return;
        }

        markHandled(run, "no-action");
    }

    /**
     * The routing key the incident's trigger used: the sandbox key if it went to the sandbox
     * integration, the primary key otherwise. Null when that key is no longer configured. A resolve
     * sent with the other key would reach an integration that never saw the incident, which accepts
     * it and drops it, so the incident would stay open while the build said it was resolved.
     */
    static @CheckForNull Secret routingKeyThatOpened(
            @NonNull PagerDutyV2RunAction open, @NonNull PagerDutyV2GlobalConfiguration cfg) {
        return open.isSandbox() ? cfg.resolveSandboxRoutingKey() : cfg.resolveRoutingKey();
    }

    static void logResolveKeyMissing(@NonNull PagerDutyV2RunAction open, @NonNull TaskListener listener) {
        listener.getLogger()
                .println("[pagerduty-v2] The " + (open.isSandbox() ? "sandbox" : "primary")
                        + " routing key that opened this incident (dedup_key=" + open.getDedupKey()
                        + ") is no longer configured; not resolving it.");
    }

    private static void markHandled(@NonNull Run<?, ?> run, @CheckForNull String reason) {
        if (run.getAction(PagerDutyV2HandledAction.class) == null) {
            run.addAction(new PagerDutyV2HandledAction(reason));
        }
    }

    static boolean shouldTriggerFor(@NonNull Config c, @NonNull Result result) {
        if (Result.SUCCESS.equals(result)) {
            return c.triggerOnSuccess;
        }
        if (Result.FAILURE.equals(result)) {
            return c.triggerOnFailure;
        }
        if (Result.UNSTABLE.equals(result)) {
            return c.triggerOnUnstable;
        }
        if (Result.ABORTED.equals(result)) {
            return c.triggerOnAbort;
        }
        if (Result.NOT_BUILT.equals(result)) {
            return c.triggerOnNotBuilt;
        }
        return false;
    }

    private static int consecutiveTriggerStreak(@NonNull Config c, @NonNull Run<?, ?> run) {
        int count = 0;
        for (Run<?, ?> r = run; r != null; r = r.getPreviousBuild()) {
            Result res = r.getResult();
            if (res == null || !shouldTriggerFor(c, res)) {
                break;
            }
            count++;
        }
        return count;
    }

    /** The newest open incident in the job's history, if any. Also used by the pipeline step. */
    static @CheckForNull PagerDutyV2RunAction findMostRecentOpenAction(@NonNull Run<?, ?> run) {
        for (Run<?, ?> r = run; r != null; r = r.getPreviousBuild()) {
            PagerDutyV2RunAction a = r.getAction(PagerDutyV2RunAction.class);
            if (a != null && a.isOpen()) {
                return a;
            }
        }
        return null;
    }

    static @NonNull String getConsoleLogTail(@NonNull Run<?, ?> run, int maxLines, int maxChars) {
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
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    static boolean isExecutorDisconnected(@NonNull Run<?, ?> run) {
        String tail = getConsoleLogTail(run, FAILURE_SIGNATURE_SCAN_LINES, FAILURE_SIGNATURE_SCAN_MAX_CHARS);
        if (tail.isEmpty()) {
            return false;
        }

        String n = tail.toLowerCase(Locale.ROOT);
        return n.contains("java.nio.channels.closedchannelexception")
                || (n.contains("backing channel") && n.contains("is disconnected"))
                || n.contains("jnlp4-connect connection")
                || n.contains("hudson.remoting.channelclosedexception")
                || n.contains("agent went offline")
                || n.contains("executor was removed")
                || n.contains("connection was broken");
    }
}
