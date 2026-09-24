# Contributing

Rebel Media uses GitHub to manage reviews of pull requests.

* If you have a trivial fix or improvement, go ahead and create a pull request,
  addressing (with `@...`) the maintainer of this repository (see
  [MAINTAINERS.md](MAINTAINERS.md)) in the description of the pull request.

* The coding conventions are listed under [Conventions](#conventions) below.
  Formatting is not a matter of style here: `make fmt` decides it.

* Sign your work to certify that your changes were created by yourself, or you
  have the right to submit it under our license. Read
  https://developercertificate.org/ for all details and append your sign-off to
  every commit message like this:

        Signed-off-by: Random J Developer <example@example.com>

  Commits must also be GPG-signed. `git commit -s -S` does both.

## Branching

`develop` is the default branch and is where pull requests are opened. Nobody
pushes to it directly. `master` only ever receives a merge from `develop` at
release time, and a merge to `master` is what cuts a release, so a pull request
against `master` from any other branch is rejected by CI.

Work happens on `feature/<short-description>` branches taken from `develop`.
That includes fixes and documentation.

## Commit messages

[Conventional Commits](https://www.conventionalcommits.org/):
`<type>(<optional scope>): <summary>`, where type is one of `feat`, `fix`,
`perf`, `refactor`, `test`, `docs`, `build`, `ci`, `chore` or `deps`. A breaking
change adds `!` after the type and a `BREAKING CHANGE:` footer.

Pull requests are merged with a merge commit, so every commit on your branch
lands on `develop` exactly as you wrote it: each one needs a conventional
message, a sign-off and a signature. The pull request title follows the same
rule, because it becomes the merge commit's title.

## Building

You need JDK 25 or newer and nothing else: `./mvnw` downloads the exact Maven
version in `.mvn/wrapper/maven-wrapper.properties`, and Maven downloads every
dependency. CI and releases build on exactly JDK 25, because the JDK's version
is written into the jar and a release must be reproducible;
`make verify-docker` runs that same JDK 25 build in a container.

```bash
make build          # compile, test and package, skipping lint and the coverage floor
make verify         # the full build CI runs
make dist           # what a release attaches, into dist/
make check          # everything CI runs, licence headers included
```

Always use `./mvnw`, never a `mvn` installed on your machine. The wrapper needs
`unzip`: without it, it downloads the tar.gz instead of the zip and the
checksum check fails.

## Testing

`make test` runs the unit tests. `make verify` also enforces the coverage floor
(`coverage.floor` in `pom.xml`); open `target/site/jacoco/index.html` after
`make cover` to see what is not covered. Raise the floor as the suite grows;
never lower it. One test class, or one method:

```bash
./mvnw test -Dtest=PayloadBuilderTest
./mvnw test -Dtest='PagerDutyV2ClientTest#doesNotRetryOn4xxOtherThan429'
```

`PluginTest` and `PagerDutyV2NotifierIntegrationTest` start a real Jenkins
with the plugin installed, which takes several seconds each; keep tests that
need `JenkinsRule` few, and test logic in plain classes. The integration tests
post to an in-process HTTP server and assert on the captured JSON, so no
PagerDuty account is needed. `make run` starts a development Jenkins at
<http://localhost:8080/jenkins> with the plugin loaded, for trying a change by
hand.

### Trying an agent disconnect by hand

The fallback that alerts when a freestyle build's agent disconnects can be
exercised against that development Jenkins with a real inbound agent in
Docker:

1. `make seed-job`, then `make run`. This adds an inbound node and a
   50-second freestyle job, both called `disconnect-test`.
2. Copy the node's secret from
   <http://localhost:8080/jenkins/computer/disconnect-test/> into a `.env`
   file as `JENKINS_SECRET=...` (git ignores `.env`), then `make agent-up`.
3. Set a routing key under **Manage Jenkins → System → PagerDuty v2**, start
   the job, and run `make agent-kill` while it is running.

Events go to real PagerDuty, so use a test service's integration key.
`make agent-down` removes the agent and its work volume.

### Fuzzing

Code that handles input nobody controls is fuzzed with
[Jazzer](https://github.com/CodeIntelligenceTesting/jazzer). The fuzz targets
are the `*Fuzzer` classes in `src/fuzz/java`: `LegacyBodyMigratorFuzzer` feeds
arbitrary build records to the 1.0.0 migration, and `PayloadBuilderFuzzer`
arbitrary job names and build numbers to the dedup key. They compile with the
tests but live outside `src/test`, which the OpenSSF Scorecard check skips as
test data.

- Every build replays each target's stored inputs, under
  `src/test/resources/io/jenkins/plugins/pagerdutyv2/fuzz/<target>/`, in
  `FuzzInputsTest`.
- The **Fuzz** workflow searches for new failing inputs for five minutes per
  target every week, and can be started by hand. A failing input is attached to
  the run.
- To handle a finding: reproduce it by adding the input to the target's
  directory, which makes `FuzzInputsTest` fail; fix the code; commit both. The
  input stays as a regression check.

## Conventions

These are the ones that get broken by accident.

* **Every Java file starts with the licence header** (first line
  `// Copyright …`). `make check-license` fails otherwise.
* **Comments explain why, in complete sentences.** Every public type has
  Javadoc; Checkstyle enforces it.
* **Formatting is palantir-java-format through Spotless.** Run `make fmt`
  rather than formatting by hand. Suppress a region only with
  `// spotless:off` / `// spotless:on` and a comment saying why.
* **Every plugin and dependency has an exact version**, in `pom.xml` or from
  the plugin BOM. Depend on another Jenkins plugin without a `<version>`; the
  BOM supplies it. No ranges and no `-SNAPSHOT` dependencies.
* **Logic lives in plain classes** such as `PayloadBuilder` and
  `PagerDutyV2Dispatcher`, not in `Builder`, `Step` or `Descriptor`
  subclasses, so it can be tested without starting Jenkins.
* **Secrets come from the Credentials plugin**, looked up by ID. Never keep
  one in a plain `String` field: it would be written to disk in `config.xml`
  or `build.xml`.
* **Anything persisted with a job or a build keeps its field names.**
  Renaming or removing a field breaks loading existing jobs and builds;
  migrate old data in `readResolve` instead.
* **The dedup key is SHA-256 of `JOB_NAME#BUILD_NUMBER` and nothing else.**
  Incidents still open in build history are resolved by that key; changing
  what goes into it would leave them open forever.
* Pipeline steps get a `@Symbol`, and `jenkins-core` stays `provided`: never
  bundle Jenkins or another plugin into the `.hpi`.
* Logging goes through `java.util.logging` (a `private static final Logger`
  named for the class), or the build's `TaskListener` for messages meant for
  the build log. Never `System.out`, `System.err` or `printStackTrace()`;
  Checkstyle rejects them.
* Tests are JUnit 5. Test classes and methods are package-private and named
  for the behaviour they check (`retriesOn429ThenSucceeds`).

## Releasing

1. On a `feature/release-x.y.z` branch, set `VERSION` to `x.y.z` and rename
   `## main / unreleased` in `CHANGELOG.md` to `## x.y.z / YYYY-MM-DD` (then add
   a fresh `## main / unreleased` above it). Merge to `develop`.
2. Open a pull request from `develop` to `master` and merge it.
3. CI tags `vx.y.z` on `master`, and the tag builds the artefacts and attaches
   them, with `sha256sums.txt`, to the GitHub release. Nobody tags by hand.

### Publishing to the Jenkins update center (not enabled)

Releases go to GitHub only, and Jenkins administrators upload the `.hpi` by
hand. To publish through the Jenkins update center, the plugin must be hosted
by the Jenkins project: file a
[hosting request](https://www.jenkins.io/doc/developer/publishing/requesting-hosting/),
after which the repository moves to the `jenkinsci` organisation and releases
are made there with Jenkins' own
[continuous delivery workflow](https://www.jenkins.io/doc/developer/publishing/releasing-cd/).
The `io.jenkins.plugins` groupId is already what hosting requires.

### Publishing to GitHub Packages (not enabled)
Add to `pom.xml`:

```xml
<distributionManagement>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/rebelcore/jenkins-plugin-pagerduty-v2</url>
  </repository>
</distributionManagement>
```

and to the `release` job in `release.yml`, which then also needs
`packages: write` in its `permissions`:

```yaml
      - uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6.0.1
        with:
          distribution: temurin
          java-version: ${{ env.JAVA_VERSION }}
          server-id: github

      - name: Publish to GitHub Packages
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          VERSION: ${{ github.ref_name }}
        run: ./mvnw -B -ntp deploy -Drevision="${VERSION#v}"
```

Consumers add the same URL as a `<repository>` and a `~/.m2/settings.xml`
server entry with a token that has `read:packages`.
