# Changelog

## 1.1.0

### Added

- **Agent-disconnect resilience for freestyle jobs.** A controller-side
  `PagerDutyV2RunListener` now fires PagerDuty events for builds whose
  post-build publisher couldn't run — most commonly when the executing agent
  disconnected mid-build and the workspace went away with it. Coordinates
  with the publisher path via a transient `PagerDutyV2HandledAction` marker,
  so a single build never produces two events. Pipeline jobs continue to use
  the opt-in `pagerDutyV2` step.

### Security

- **Routing key no longer persisted to disk.** `PagerDutyV2RunAction` previously
  serialized the full PagerDuty trigger body — including the integration key —
  into `build.xml`. The action now stores only the `payload` JSON; the routing
  key is freshly resolved from credentials and injected at send time for both
  trigger and resolve. Existing builds with the legacy on-disk format are
  migrated transparently on load via `readResolve()` (legacy field is read
  once, payload extracted, then cleared). Side benefit: rotating the routing
  key no longer breaks resolves on older open incidents.

### Added

- Retry on transient HTTP errors (429 and 5xx) with exponential backoff and
  jitter, up to 4 attempts.
- Honor Jenkins `ProxyConfiguration` when posting to PagerDuty.
- Validate the global Events API endpoint URL (requires `http(s)://` and a
  host; warns on plain HTTP).
- Validate pipeline step `action` and `severity` against the allowed sets at
  configuration time, with dropdowns in the step config UI.

### Changed

- Reuse a single process-wide `OkHttpClient` instead of constructing one per
  event (eliminates per-call thread-pool / connection-pool allocation).
- Migrated credentials lookups from deprecated
  `CredentialsProvider.lookupCredentials` + `ACL.SYSTEM` to
  `lookupCredentialsInItemGroup` + `ACL.SYSTEM2`.
- Credential dropdowns now use `StandardListBoxModel.includeMatchingAs(...)` +
  `includeCurrentValue(...)`, are `@POST`-annotated, and gracefully degrade
  to "current value only" for non-admin users.
- `PagerDutyV2GlobalConfiguration` now overrides `configure(StaplerRequest2,
  JSONObject)` instead of calling `save()` from each setter, so a single
  System-config save writes to disk once.

### Fixed

- Corrected `PayloadBuilder.dedupKey` Javadoc (was described as
  `JOB_NAME:BRANCH_NAME`; actual behavior is `SHA-256(JOB_NAME#BUILD_NUMBER)`).
- Fixed mixed-tab indentation in `PagerDutyV2Notifier` and
  `PagerDutyV2RunAction`.
- Removed redundant `Math.max` normalization in the trigger path
  (already enforced by the setter).

### Tests

- Added `LegacyBodyMigratorTest` covering the on-disk legacy-format migration.
- Added `PagerDutyV2ClientTest` covering retry-then-succeed on 503 and 429,
  no-retry on 400, and give-up after max attempts on persistent 500.
- `PagerDutyV2NotifierIntegrationTest` now asserts the persisted action
  contains neither the routing key nor a `routing_key` field.

## 1.0.0

Initial Release
