# PagerDuty v2 Jenkins Plugin

A Jenkins plugin that sends incidents to **PagerDuty Events API v2** from Jenkins builds.

It supports:

- **Freestyle / classic jobs** via a post-build **Notifier**
- **Pipeline** via a `pagerDutyV2` step

The plugin implements a reliable **trigger → resolve** workflow by generating a `dedup_key` per build, storing the **exact JSON body** used for the trigger on that build, and replaying that payload for the resolve. This avoids mismatched dedup keys and makes “resolve” deterministic.

---

## Features

### Global (system) configuration

Configured once in **Manage Jenkins → System → PagerDuty v2**:

- **Disable PagerDuty notifications**: master kill-switch (no events sent)
- **Require Tags in job configuration**: prevents saving jobs without Tags
- **Events API v2 endpoint**: defaults to `https://events.pagerduty.com/v2/enqueue`
- **Routing key credential (Secret Text)**: uses a Jenkins “Secret Text” credential (PagerDuty *integration key*)
- **Service list (optional)**: one-per-line or comma-separated; when present, job config shows a dropdown and enforces membership

### Freestyle notifier capabilities

Configured per-job under **Post-build Actions → PagerDuty v2**:

- Choose the **Service** (text field or dropdown depending on global config)
- Optional **custom summary** (override default summary text)
- Optional **Tags** (comma-separated string)
- Choose **severity** for trigger events (`critical|error|warning|info`)
- “Trigger on” toggles for build results:
  - `SUCCESS`, `FAILURE`, `UNSTABLE`, `ABORTED`, `NOT_BUILT`
- **Consecutive builds before trigger**:
  - only triggers after N consecutive builds that match a trigger condition
  - helps avoid noise from flaky/one-off failures
- **Resolve when back to normal (SUCCESS)**:
  - sends resolve when build returns to `SUCCESS` and an open incident exists
- Optional **console log tail** in trigger events:
  - adds the last N lines of console output to `custom_details.console_log_tail`
  - truncated to a safe size to avoid exceeding event payload limits

### Pipeline step capabilities

- Step: `pagerDutyV2(action: 'trigger'|'resolve', severity: 'critical')`
- Stores trigger JSON on the current run and replays for resolve
- Resolve finds the **most recent open incident** in build history for that job

---

## Installation

### Build and install locally (HPI)

From the repository root:

```bash
mvn -U -DskipTests package
```

The plugin artifact is produced under:

- `target/pagerduty-v2.hpi`

Install in Jenkins via:

- **Manage Jenkins → Manage Plugins → Advanced → Upload Plugin**

Restart Jenkins if required.

> Note: This repository is structured like a typical Jenkins plugin project using the Jenkins Plugin parent POM and Jenkins test harness.

---

## PagerDuty setup (routing key)

1. In PagerDuty, create or use an existing integration from the **AIOps Event Orchestration**.
2. Copy the **Integration Key** (also called routing key).
3. In Jenkins, create a **Secret Text** credential holding that key:
   - **Manage Jenkins → Credentials → (domain) → Add Credentials**
   - Kind: **Secret text**
   - Secret: *(PagerDuty integration key)*
4. In **Manage Jenkins → System → PagerDuty v2**, select that credential in **Routing key credential (Secret Text)**.

---

## Configuration (Global)

Open **Manage Jenkins → System → PagerDuty v2**.

### 1) Endpoint URL

- Default: `https://events.pagerduty.com/v2/enqueue`
- Override only if:
  - using a proxy, test endpoint, or internal relay

### 2) Disable notifications

If enabled, the plugin prints:

- `[pagerduty-v2] PagerDuty is disabled in system configuration; skipping.`

…and sends nothing.

### 3) Require Tags

If enabled, jobs cannot be saved unless Tags are provided in the notifier configuration.

### 4) Service list (optional)

Enter either:

- one per line, or
- comma-separated, or a mix of both

Example (case-sensitive):

```
payments
search, onboarding
ops-tools
```

If configured:

- Freestyle jobs show a **dropdown** for service selection
- The job config is validated server-side: the chosen service must match the allowed list

If not configured:

- Freestyle jobs show a **free text** Service field

---

## Configuration (Freestyle / classic jobs)

Add **Post-build Action → PagerDuty v2**.

### Service (required)

- If global Service list exists, select from dropdown (placeholder `-- select a service --` is invalid).
- Otherwise, enter a service string.

The selected service is included in two places:

- `payload.component` (PagerDuty “component” field)
- `payload.custom_details.service`

### Tags (optional or required depending on global setting)

- Enter comma-separated tags (stored as a string)
- If global “Require Tags” is enabled, this field is required

Example:

```
team=oncall,env=prod,app=payments
```

### Custom summary (optional)

Enable **Use custom summary** to provide a custom summary string. If blank, the default summary is used:

- `Jenkins <JOB_NAME> build <BUILD_NUMBER>`

### Severity (trigger events)

Select one of:

- `critical`, `error`, `warning`, `info`

This sets `payload.severity`.

### Console log tail (optional)

Enable “Include console log tail in trigger events”:

- Lines included: configurable (`consoleLogTailLines`, default 200)
- Added to: `payload.custom_details.console_log_tail`
- Truncated to **~4000 characters** to reduce likelihood of hitting payload size limits

### Trigger conditions

Choose when to trigger:

- Trigger on `SUCCESS` / `FAILURE` / `UNSTABLE` / `ABORTED` / `NOT_BUILT`

### Consecutive builds before trigger

If set to N > 1, the plugin only triggers after N consecutive builds that match the trigger condition.

Example:

- `Trigger on FAILURE = true`
- `Consecutive builds before trigger = 3`

…means PagerDuty triggers only on the 3rd consecutive failure.

### Resolve when back to normal

If enabled:

- On `SUCCESS`, the plugin searches backwards for the most recent open incident and sends `resolve`.

If an open incident already exists and a trigger condition happens again, the plugin will **not re-trigger**; it logs:

- `[pagerduty-v2] Open incident already exists ...; not triggering again.`

### Agent-disconnect resilience (freestyle)

When an agent disconnects mid-build, Jenkins may not be able to run the post-build publisher (it requires a live workspace). To avoid silently losing alerts in that case, a controller-side `RunListener` fires after every freestyle build's final state is recorded and dispatches the same trigger/resolve logic if the publisher didn't run. The two paths coordinate through a transient `PagerDutyV2HandledAction` marker so a single build never produces two events.

This applies to freestyle / matrix jobs only. Pipeline authors who need disconnect resilience should wrap their build in `catchError` or use `post { failure { pagerDutyV2(action: 'trigger') } }`, since the pipeline step is opt-in by design.

---

## Pipeline usage

The plugin provides a pipeline step named **`pagerDutyV2`**.

### Trigger

```groovy
pagerDutyV2(action: 'trigger', severity: 'critical')
```

### Resolve

```groovy
pagerDutyV2(action: 'resolve')
```

- Resolve searches backward through the job’s build history to find the most recent **open** trigger action and resolves it.
- If none exists, it prints:

  `No open incident found; nothing to resolve.`

### Example pipeline snippet

```groovy
pipeline {
  agent any
  stages {
    stage('Build') {
      steps {
        sh 'make test'
      }
    }
  }
  post {
    failure {
      pagerDutyV2(action: 'trigger', severity: 'error')
    }
    success {
      pagerDutyV2(action: 'resolve')
    }
  }
}
```

> Note: The pipeline step does not currently accept `service`/`tags` parameters; it sends the base payload built from Jenkins environment variables and the provided severity.

---

## How trigger/resolve works

### Dedup key

The `dedup_key` is generated in `PayloadBuilder.dedupKey(env)` as a SHA-256 hash of:

- `JOB_NAME#BUILD_NUMBER`

This produces:

- a **64-character lowercase hex** string
- stable for the build lifecycle (and reproducible for tests)

### Payload replay (why it matters)

On trigger, the plugin stores the full JSON body used for the trigger in a `PagerDutyV2RunAction` attached to that build.

On resolve:

- the stored JSON is loaded
- `event_action` is changed to `"resolve"`
- the event is posted
- the stored action is marked `open=false`

This approach ensures:

- resolve uses the same `dedup_key`
- resolve includes the same routing key and payload structure
- accidental payload drift does not break resolves

---

## Payload details

Events are posted to:

- `POST <endpointUrl>` (default: PagerDuty Events API v2 `/v2/enqueue`)
- Content-Type: `application/json`

### Request body structure

The body is constructed in `PayloadBuilder.buildBody(...)`:

```json
{
  "routing_key": "YOUR_ROUTING_KEY",
  "event_action": "trigger",
  "dedup_key": "64-hex-sha256",
  "payload": {
    "summary": "Jenkins job build 123",
    "source": "http://your-jenkins/",
    "severity": "error",
    "component": "svc-a",
    "custom_details": {
      "job_name": "job",
      "build_number": "123",
      "build_url": "http://jenkins/job/job/123/",
      "node_name": "agent-1",
      "git_url": "https://example/repo.git",
      "git_branch": "main",
      "tags": "team=oncall,env=prod",
      "service": "svc-a",
      "result": "FAILURE",
      "consecutive_streak": 2,
      "executor_disconnected": true,
      "failure_reason": "executor_disconnected",
      "console_log_tail": ".... (optional)"
    }
  }
}
```

Notes:

- `payload.component` is set from the job’s **Service** value (Freestyle notifier).
- `custom_details.git_url` and `custom_details.git_branch` are included only if the environment variables exist.
- `custom_details.executor_disconnected` and `custom_details.failure_reason=executor_disconnected` are included when remoting disconnect signatures are detected in recent build log lines.
- `console_log_tail` is only included when enabled and the build is not `SUCCESS`.

---

## Troubleshooting

### “No routing key credential configured; skipping.”

- Go to **Manage Jenkins → System → PagerDuty v2**
- Set **Routing key credential (Secret Text)** to a valid credential ID

### “Service is required; skipping PagerDuty notification.”

- In job configuration, ensure **Service** is set
- If you configured a global Service list, ensure you select from the dropdown and not the placeholder

### HTTP failures (enqueue failed)

If PagerDuty responds with a non-2xx status, the plugin throws an `IOException` including the HTTP code and response body snippet.

Common causes:

- wrong routing key
- endpoint blocked by firewall/proxy
- TLS interception / proxy issues
- PagerDuty outage (rare)

### Resolve didn’t happen

Resolve requires all of the following (Freestyle notifier):

- `Resolve when back to normal` enabled
- Current build result is `SUCCESS`
- There is a previously stored open `PagerDutyV2RunAction` in build history

Pipeline step resolve requires:

- a prior `pagerDutyV2(action: 'trigger')` that stored an open action

---

## Development

### Requirements

- Java and Maven compatible with the Jenkins baseline defined in `pom.xml`
- Network access to download Maven dependencies

### Build

```bash
mvn -U -DskipTests package
```

### Run tests

```bash
mvn -U test
```

### Test suite overview

The test suite is designed to cover both “pure logic” and Jenkins integration:

- `PayloadBuilderTest`
  - dedup key format and stability
  - payload field inclusion (git fields only when present)
  - request body shape
- `PagerDutyV2GlobalConfigurationTest`
  - parsing and de-duplication of service choices input
- `PagerDutyV2NotifierIntegrationTest` (JenkinsRule)
  - triggers a real Freestyle build failure and verifies an HTTP POST is made
  - verifies resolve replay reuses the same `dedup_key`
  - verifies `consecutiveBuildsBeforeTrigger` delays triggering until threshold is reached
- `PayloadReplayTest`
  - focused test for replay/resolve mutation behavior

Integration tests use an in-process local HTTP server to capture JSON bodies (no external PagerDuty dependency).

---

## Project structure

- `src/main/java/...`
  - `PagerDutyV2GlobalConfiguration`: global settings + credentials lookup
  - `PagerDutyV2Notifier`: freestyle notifier logic (trigger/resolve, streak threshold, optional log tail)
  - `PagerDutyV2Step`: pipeline step for trigger/resolve
  - `PagerDutyV2Client`: OkHttp-based POST client to PagerDuty endpoint
  - `PayloadBuilder`: creates request payloads + dedup key
  - `PagerDutyV2RunAction`: stores trigger JSON and open/closed state on builds
- `src/main/resources/...`
  - Jelly UI for global config, notifier config, and pipeline step config
- `src/test/java/...`
  - unit + Jenkins integration tests

---

## Notes / limitations

- The pipeline step currently supports:
  - `action` (`trigger` or `resolve`)
  - `severity` (used only for trigger)
- The freestyle notifier supports richer job-level fields (service/tags/custom summary/log tail).
- Payload size limits are controlled by PagerDuty; console log tail is truncated defensively, but very large environments or unusual custom fields can still exceed limits.

---

## License

This repository’s license (if any) should be added/confirmed at the root (e.g., `LICENSE`). If you intend to publish to the Jenkins plugin index, ensure licensing and plugin metadata follow Jenkins project guidelines.
