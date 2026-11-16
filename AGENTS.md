## Agent skills

### Issue tracker

Issues are tracked as local markdown files under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`), recorded as a `Status:` line in each issue. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.

## Build, test, review

Kör `./mvnw -o verify` (Checkstyle, enhetstester, integrationstester). Integrationstesterna binder en lokal port, vilket sandboxen kräver `sandbox.network.allowLocalBinding: true` för (satt i `.claude/settings.json`). Pre-commit-hooken ligger i `.githooks` (`git config core.hooksPath .githooks`). Granskare läser `CODING_STANDARDS.md`.

## Spec

Protokolltabeller, permission-flöde och liveuppdatering: `docs/POC-SPEC.md`. Krav och domänregler: `docs/PRD.md`.
