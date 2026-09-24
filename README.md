<div align="center">

# PagerDuty v2

[![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/rebelcore/jenkins-plugin-pagerduty-v2/test.yml?style=for-the-badge&color=22C55E)](https://github.com/rebelcore/jenkins-plugin-pagerduty-v2/actions/workflows/test.yml)
[![GitHub Release](https://img.shields.io/github/v/release/rebelcore/jenkins-plugin-pagerduty-v2?style=for-the-badge&color=22C55E)](https://github.com/rebelcore/jenkins-plugin-pagerduty-v2/releases/latest)
[![GitHub Downloads](https://img.shields.io/github/downloads/rebelcore/jenkins-plugin-pagerduty-v2/total?style=for-the-badge&color=1D63ED)](https://github.com/rebelcore/jenkins-plugin-pagerduty-v2/releases)
[![Jenkins](https://img.shields.io/badge/Jenkins-2.568.1%2B-D24939?style=for-the-badge)](https://www.jenkins.io/changelog-stable/)
[![License](https://img.shields.io/badge/license-Apache%202-g?style=for-the-badge&color=8B5CF6)](LICENSE)

Jenkins plugin that sends PagerDuty Events API v2 trigger
and resolve events from Jenkins builds.

</div>

---

A Jenkins plugin. It needs Jenkins 2.568.1 or newer, and supports:

- **Freestyle / classic jobs** via a post-build **Notifier**
- **Pipeline** via a `pagerDutyV2` step

It implements a reliable **trigger → resolve** workflow: every event for a
build carries the same `dedup_key`, the trigger's payload is stored on that
build, and the resolve replays it. A resolve therefore always matches its
trigger.

## Install

Download `pagerduty-v2-<version>.hpi` from the
[releases page](https://github.com/rebelcore/jenkins-plugin-pagerduty-v2/releases), verify it against `sha256sums.txt`,
and upload it under **Manage Jenkins → Plugins → Advanced settings → Deploy
Plugin**. Restart Jenkins when it asks. Each release also carries the same
file as `pagerduty-v2.hpi`, so a script can always fetch
`releases/latest/download/pagerduty-v2.hpi`.

Check your Jenkins version first: the upload does not. On a Jenkins older than
2.568.1 the plugin installs, but fails to load after the restart.

## Usage

### PagerDuty setup (routing key)

1. In PagerDuty, create or use an existing integration from the **AIOps Event Orchestration**.
2. Copy the **Integration Key** (also called routing key).
3. In Jenkins, create a **Secret Text** credential holding that key:
   - **Manage Jenkins → Credentials → (domain) → Add Credentials**
   - Kind: **Secret text**
   - Secret: *(PagerDuty integration key)*
4. In **Manage Jenkins → System → PagerDuty v2**, select that credential in **Routing key credential (Secret Text)**.

### Global configuration

Configured once in **Manage Jenkins → System → PagerDuty v2**:

- **Disable PagerDuty notifications**: master kill-switch. When set, the plugin
  logs `[pagerduty-v2] PagerDuty is disabled in system configuration; skipping.`
  and sends nothing.
- **Require Tags in job configuration**: jobs cannot be saved unless Tags are
  provided in the notifier configuration.
- **Events API v2 endpoint**: defaults to `https://events.pagerduty.com/v2/enqueue`.
  Override it only for a proxy, a test endpoint or an internal relay.
- **Routing key credential (Secret Text)**: the Jenkins "Secret Text"
  credential holding the PagerDuty integration key.
- **Service list (optional)**: one per line, comma-separated, or a mix of both.
  Example (case-sensitive):

  ```
  payments
  search, onboarding
  ops-tools
  ```

  When set, freestyle jobs show a **dropdown** for the service and the job
  configuration is validated server-side against the list. When empty, they
  show a **free text** Service field.

### Freestyle / classic jobs

Add **Post-build Action → PagerDuty v2**.

#### Service (required)

- If a global Service list exists, select from the dropdown (the placeholder `-- select a service --` is invalid).
- Otherwise, enter a service string.

The selected service is included in two places:

- `payload.component` (PagerDuty "component" field)
- `payload.custom_details.service`

#### Tags (optional or required depending on global setting)

- Enter comma-separated tags (stored as a string)
- If global "Require Tags" is enabled, this field is required

Example:

```
team=oncall,env=prod,app=payments
```

#### Custom summary (optional)

Enable **Use custom summary** to provide a custom summary string. If blank, the default summary is used:

- `Jenkins <JOB_NAME> build <BUILD_NUMBER>`

#### Severity (trigger events)

Select one of `critical`, `error`, `warning`, `info`. This sets `payload.severity`.

#### Console log tail (optional)

Enable "Include console log tail in trigger events":

- Lines included: configurable (`consoleLogTailLines`, default 200)
- Added to: `payload.custom_details.console_log_tail`
- Truncated to **~4000 characters** to reduce likelihood of hitting payload size limits

#### Trigger conditions

Choose when to trigger: `SUCCESS` / `FAILURE` / `UNSTABLE` / `ABORTED` / `NOT_BUILT`.

#### Consecutive builds before trigger

If set to N > 1, the plugin only triggers after N consecutive builds that match the trigger condition.

Example:

- `Trigger on FAILURE = true`
- `Consecutive builds before trigger = 3`

…means PagerDuty triggers only on the 3rd consecutive failure.

#### Resolve when back to normal

If enabled:

- On `SUCCESS`, the plugin searches backwards for the most recent open incident and sends `resolve`.

If an open incident already exists and a trigger condition happens again, the plugin will **not re-trigger**; it logs:

- `[pagerduty-v2] Open incident already exists ...; not triggering again.`

While an incident is open, the build that triggered it is marked **Keep this build
forever**, because that build is the only record of the incident: if build
retention deleted it, the incident could never be resolved. The mark is removed
when the incident is resolved, unless the build was already kept.

#### Agent-disconnect resilience

When an agent disconnects mid-build, the build has no workspace by the time its post-build actions run. This post-build action does not need one, so it still runs and sends the event from the controller. As a further fallback, a controller-side `RunListener` fires after every freestyle build's final state is recorded and dispatches the same trigger/resolve logic if the post-build action did not run at all. The two paths coordinate through a transient `PagerDutyV2HandledAction` marker so a single build never produces two events.

This applies to freestyle / matrix jobs only. Pipeline authors who need disconnect resilience should wrap their build in `catchError` or use `post { failure { pagerDutyV2(action: 'trigger') } }`, since the pipeline step is opt-in by design.

### Pipeline

The plugin provides a pipeline step named **`pagerDutyV2`**.

```groovy
pagerDutyV2(action: 'trigger', severity: 'critical')
pagerDutyV2(action: 'resolve')
```

- Resolve searches backward through the job's build history to find the most recent **open** trigger action and resolves it.
- If none exists, it prints `No open incident found; nothing to resolve.`

Example:

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

The pipeline step accepts only `action` (`trigger` or `resolve`) and `severity`
(used only for trigger); it does not take `service` or `tags`. It sends the
base payload built from Jenkins environment variables.

### How trigger and resolve work

#### Dedup key

The `dedup_key` is a SHA-256 hash of `JOB_NAME#BUILD_NUMBER`: a 64-character
lowercase hex string, stable for the build's lifecycle and reproducible in
tests.

#### Payload replay

On trigger, the plugin stores the event payload and dedup key in a
`PagerDutyV2RunAction` attached to that build. The routing key is not stored:
it is read from the credential each time an event is sent.

On resolve:

- the stored payload is loaded
- `event_action` is set to `"resolve"`
- the event is posted with the current routing key
- the stored action is marked closed

This ensures that a resolve uses the same `dedup_key` and payload structure as
its trigger, that payload drift cannot break resolves, and that rotating the
routing key does not strand older incidents.

### Payload details

Events are posted as `POST <endpointUrl>` with `Content-Type: application/json`.
The request body looks like this:

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

- `payload.component` is set from the job's **Service** value (Freestyle notifier).
- `custom_details.git_url` and `custom_details.git_branch` are included only if the environment variables exist.
- `custom_details.executor_disconnected` and `custom_details.failure_reason=executor_disconnected` are included when remoting disconnect signatures are detected in recent build log lines.
- `console_log_tail` is only included when enabled and the build is not `SUCCESS`.
- Payload size limits are set by PagerDuty. The console log tail is truncated
  defensively, but very large environments or unusual custom fields can still
  exceed them.

### Troubleshooting

#### "No routing key credential configured; skipping."

- Go to **Manage Jenkins → System → PagerDuty v2**
- Set **Routing key credential (Secret Text)** to a valid credential ID

#### "Service is required; skipping PagerDuty notification."

- In job configuration, ensure **Service** is set
- If you configured a global Service list, ensure you select from the dropdown and not the placeholder

#### HTTP failures (enqueue failed)

Transient errors (429 and 5xx) are retried with exponential backoff, up to 4
attempts. If PagerDuty still responds with a non-2xx status, the plugin throws
an `IOException` including the HTTP code and a snippet of the response body.

Common causes:

- wrong routing key
- endpoint blocked by firewall/proxy
- TLS interception / proxy issues
- PagerDuty outage (rare)

#### Resolve didn't happen

Resolve requires all of the following (Freestyle notifier):

- `Resolve when back to normal` enabled
- Current build result is `SUCCESS`
- There is a previously stored open `PagerDutyV2RunAction` in build history

Pipeline step resolve requires:

- a prior `pagerDutyV2(action: 'trigger')` that stored an open action

## Development

Requires a JDK 25 (`make verify-docker` runs the build in a container instead).
Everything else, Maven included, is downloaded by `./mvnw`.

```bash
make test       # the unit tests
make verify     # tests, coverage floor, Spotless, Checkstyle, enforcer
make fmt        # format the sources
make run        # a Jenkins at http://localhost:8080/jenkins with the plugin loaded
make check      # everything CI runs
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the branching model, commit rules,
coding conventions and release process.

## License

Apache-2.0. See [LICENSE](LICENSE).
