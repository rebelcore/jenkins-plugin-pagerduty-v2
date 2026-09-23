<!--
Keep this short — the checklist matters more than the prose. See CONTRIBUTING.md.
Pull requests go feature/<name> -> develop. Only develop is ever merged into master.
-->

## Summary

<!-- What does this change, and why? Link the issue it closes. -->

Closes #

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Breaking change
- [ ] Documentation
- [ ] Build, CI or tooling

## Checklist

- [ ] Title is a Conventional Commit (`feat(scope): ...`, `fix: ...`)
- [ ] Every commit is signed off and GPG-signed (`git commit -s -S`)
- [ ] `make check` passes (licence headers, Spotless, Checkstyle, tests, coverage floor, enforcer)
- [ ] New behaviour has tests; public types have Javadoc
- [ ] **If users will notice this change**, an entry under `## main / unreleased`
      in `CHANGELOG.md` (`[CHANGE]`, `[FEATURE]`, `[ENHANCEMENT]` or `[BUGFIX]`)
- [ ] A change to a public type or method is backwards compatible, or marked as a breaking change
- [ ] Tried in a real Jenkins with `make run`

### If this is a release (develop -> master)

- [ ] `VERSION` bumped and `CHANGELOG.md` has `## <VERSION> / <YYYY-MM-DD>`

## Notes for the reviewer

<!-- Trade-offs taken, alternatives rejected, follow-up work. -->
