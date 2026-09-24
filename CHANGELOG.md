# Changelog

Entries use the Prometheus format. Each release heading is `## <version> / <date>`
and must match the VERSION file when develop is merged into master.

Each entry is one line, starting with one of these tags:
- `[CHANGE]` is anything a user must act on (breaking).
- `[FEATURE]` is new capability.
- `[ENHANCEMENT]` improves existing capability.
- `[BUGFIX]` fixes a bug.

Only write changes a user will notice. Internal changes appear in the generated
release notes from pull request labels, not here.

## main / unreleased

## 1.2.0 / 2026-09-24

* [CHANGE] Requires Jenkins 2.568.1 or newer (was 2.541.1).
* [CHANGE] Releases attach `pagerduty-v2-<version>.hpi`, the same file as `pagerduty-v2.hpi`, and `sha256sums.txt`. The `.jpi` copy and `checksums.sha256` are no longer published.
* [BUGFIX] Security: builds that triggered an event under 1.0.0 still held the routing key in their `build.xml`. Each such build is now rewritten without it the first time it loads. If you ran 1.0.0, rotate the integration key if copies of `JENKINS_HOME`, such as backups, may have been exposed.
* [BUGFIX] A freestyle build whose agent disconnected before its post-build actions no longer fails with "no workspace": the post-build action runs without one and sends the event itself, and a green build stays green.
* [BUGFIX] When sending an event fails, the disconnect fallback no longer sends it again, which doubled the retries and could leave a green build red without its resolve.
* [BUGFIX] The build that opened an incident is kept until the incident is resolved. Build retention could delete it first, leaving the incident open forever and letting the next failure open a second one.
* [BUGFIX] Matrix jobs no longer send an extra event for the parent build on top of the events from their configurations.
* [BUGFIX] The `pagerDutyV2` pipeline step no longer triggers a second incident while one is already open for the job.
* [BUGFIX] Events follow the Jenkins proxy configuration on every request: proxy credentials and the no-proxy list now apply, and a proxy change takes effect without a restart.
* [BUGFIX] A resolve uses the routing key its trigger used, primary or sandbox, instead of the job's current sandbox setting, which could send it to an integration that silently dropped it. If that key is no longer configured, the incident stays open and the build log says why.
* [BUGFIX] A 1.0.0 build record whose stored payload is not a JSON object no longer makes every resolve of its incident fail; the payload is read as empty, as other unreadable records already were.

## 1.1.0 / 2026-04-28

* [FEATURE] Freestyle jobs still alert when the agent disconnects mid-build: a controller-side listener sends the event when the post-build action could not run, and a build never sends two.
* [FEATURE] Retry transient HTTP errors (429 and 5xx) with exponential backoff and jitter, up to 4 attempts.
* [FEATURE] Honour the Jenkins proxy configuration when posting to PagerDuty.
* [ENHANCEMENT] Validate the Events API endpoint URL in the system configuration: `http(s)://` and a host are required, and plain HTTP shows a warning.
* [ENHANCEMENT] Validate the pipeline step's `action` and `severity`, with dropdowns in the step configuration.
* [ENHANCEMENT] Credential dropdowns list only matching credentials, keep the current value, require POST, and show non-administrators the current value only.
* [ENHANCEMENT] Share one HTTP client across all events instead of creating one per event.
* [ENHANCEMENT] Saving the system configuration writes the plugin's settings once instead of once per field.
* [BUGFIX] Security: the routing key is no longer written to `build.xml`. Only the event payload is stored, and the key is read from the credential each time an event is sent. Builds recorded by 1.0.0 still load, and rotating the key no longer breaks resolving older incidents.

## 1.0.0 / 2026-01-27

* [FEATURE] Initial release.
