# Project Instructions

## Commit / PR conventions

This repo uses [release-please](https://github.com/googleapis/release-please) to automate versioning and changelog generation from commit history. PRs are squash-merged, so **the PR title is the commit message that matters**.

When opening a PR in this repo (`gh pr create` or otherwise), always title it using [Conventional Commits](https://www.conventionalcommits.org/):

- `feat: ...` — a new feature (bumps the minor version)
- `fix: ...` — a bug fix (bumps the patch version)
- `docs:`, `chore:`, `refactor:`, `test:`, `ci:`, `style:`, `perf:` — no version bump, but still categorized in the changelog
- `feat!: ...` or a `BREAKING CHANGE:` footer — a breaking change (bumps the major version, once past 1.0.0)

A required "Semantic Pull Request" check enforces this on every PR — pick the type that matches the actual change.
