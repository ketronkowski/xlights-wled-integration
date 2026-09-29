# Contributing to xLights ⇄ WLED

Thanks for taking an interest in this project! It's a small personal tool that grew out of one Christmas-light setup's very specific need, so it's maintained solo — but bug reports, ideas, and pull requests are genuinely welcome. This doc covers how to file an issue and how to send a PR.

## Found a bug, or have an idea?

Open an issue! Here's how:

1. Head to the [Issues tab](https://github.com/ketronkowski/xlights-wled-integration/issues) and click **New issue**.
2. First, take a quick look through [existing issues](https://github.com/ketronkowski/xlights-wled-integration/issues?q=is%3Aissue) to see if someone's already reported the same thing — feel free to add a 👍 or a comment there instead of opening a duplicate.
3. Give it a clear, specific title (`"Restore Latest does nothing on Safari"` beats `"bug"`).
4. In the description, the more context the better:
   - **Bug reports**: what you did, what you expected, what actually happened. Browser/OS if it's a UI issue, and the WLED firmware version if it's a device-communication issue. Screenshots are great.
   - **Feature ideas**: what problem you're trying to solve, not just the solution you have in mind — sometimes there's a simpler path to the same goal.

That's it — no template to wrestle with, no required fields. Just enough detail that it's actionable.

## Want to send a pull request?

Absolutely, here's the flow:

1. **Fork the repo** and create a branch off `main` for your change (`git checkout -b fix/whatever-it-is`).
2. **Make your change.** If it's a backend change, add or update a test alongside it where it makes sense — `./gradlew test` should pass before you open the PR (this repo's CI will also check).
3. **Open the pull request** against `main`, and describe *what* changed and *why* — the why matters more than the what, since the diff already shows the what. If it fixes an open issue, mention it (e.g. `Fixes #12`) so it links up automatically.
4. **CI runs automatically** on your PR (tests only — it won't try to build/push a container image or touch anything from a fork). Green check means it's ready for a look.
5. **I'll review it.** This is a low-traffic solo-maintained project, so response time varies — I'll comment with feedback, request changes, or merge it. Every PR (including my own, these days) goes through this same review flow — nothing merges straight to `main` without one, so please don't be surprised if it sits for a bit before I get to it.

A few things that make review faster:
- **Keep it focused.** A PR that does one thing is much easier to review (and much easier to revert if something's wrong) than one that bundles a feature with an unrelated refactor.
- **Match the existing style.** No linter/formatter is enforced here — just take a look at the surrounding code and follow its conventions.
- **Explain non-obvious decisions in the PR description**, not just in code comments — it helps me understand your reasoning during review even before I open the diff.

## Branch protection

`main` has the following protections enabled (via GitHub branch protection rules), enforced for everyone including the maintainer:

- **A passing `Test` status check is required**, and it's re-checked against the latest `main` before merge (`strict` mode) — a PR that's gone stale behind other merges has to re-run, not just have passed once.
- **A pull request is required for every change** — no direct pushes to `main`, no exceptions for admins (`enforce_admins`). This is deliberate, not an oversight: it means even a one-line maintainer fix goes through CI and a reviewable diff.
- **Required approving review count is 0.** This looks like "no review needed," but it's actually a workaround: GitHub never allows self-approval, and this repo currently has one collaborator, so requiring ≥1 approval would make solo maintenance impossible. The PR + CI gate is still enforced; only the human-approval step is skipped out of necessity. If a second regular contributor joins, this should go up to 1.
- **Conversation resolution is required** before merging — any review comment thread has to be marked resolved.
- **Force-pushes and branch deletion are disabled** on `main`.

## Local setup

See the [README](README.md#running-locally) for how to build and run the app locally, and how the pieces fit together before you dive into a change.

## Code of conduct

Be kind, assume good faith, and keep feedback constructive — pretty much the standard open-source social contract. There's no separate formal document for this; just don't be a jerk and we'll get along fine.
