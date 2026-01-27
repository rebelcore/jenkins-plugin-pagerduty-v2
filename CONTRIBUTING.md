# Contributing

Thanks for your interest in improving the PagerDuty v2 Jenkins plugin.

## Submitting changes

- Open a pull request against the default branch.
- In the PR description, mention the maintainer(s) listed in
  [MAINTAINERS.md](MAINTAINERS.md) so reviews don’t get missed.
- Include a short summary of what changed and *why*.

## Local build

This repository is a Maven-based Jenkins plugin project.

```bash
mvn -B -ntp verify
```

## Style and conventions

- Prefer small, focused PRs.
- Follow the conventions in existing code and Jenkins plugin ecosystem best
  practices.

## Developer Certificate of Origin (DCO)

Sign your work to certify that your changes were created by yourself, or you
have the right to submit it under the project license. Read
https://developercertificate.org/ for all details and append your sign-off to
every commit message like this:

    Signed-off-by: Random J Developer <example@example.com>
