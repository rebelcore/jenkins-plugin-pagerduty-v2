# Security Policy

The Rebel Media security policy, which takes precedence over anything here, is
published at <https://docs.rebelcore.org/security>.

## Supported versions

| Version | Supported |
|---------|-----------|
| 1.x     | Yes       |

Fixes land on the latest minor release. There are no long-term support branches.

## Reporting a vulnerability

Report privately. Do not open a public issue, and do not disclose the problem
publicly until a fix is available.

Use [GitHub private vulnerability reporting](https://github.com/rebelcore/jenkins-plugin-pagerduty-v2/security/advisories/new),
or email <security@rebelcore.org> if you would rather not use GitHub.

A useful report says what the problem is, how to reproduce it, and what an
attacker gains. The plugin version and the Jenkins version help.

## What happens next

1. We acknowledge the report within 5 business days.
2. We confirm the issue and tell you whether we agree on the severity, normally
   within 10 business days.
3. We prepare a fix and a release. Coordinated disclosure is 90 days from the
   acknowledgement, or sooner once a release is out.
4. We publish a GitHub Security Advisory and credit you, unless you would
   prefer otherwise.

If a report is declined we will say why. If you disagree, say so.

## Scope

In scope: this project's own code, and its build and release workflows.

Out of scope: vulnerabilities in Jenkins itself or in other plugins, which
should be reported to the [Jenkins security team](https://www.jenkins.io/security/),
and vulnerabilities in third-party dependencies that are already public. Those
are tracked by Dependabot and dependency review.
