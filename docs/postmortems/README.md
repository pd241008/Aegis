# 🔥 Aegis Postmortems

> **Status:** Active — 2 incidents documented.

---

## ✍️ How to Write a Postmortem

Aegis postmortems follow the format in the
[Design-Dungeons engineering playbook](https://github.com/pd241008/Design-Dungeons)
(`02-postmortems/`). No local template is kept — the playbook is the source of
truth.

- One incident per file, named `YYYY-MM-DD-short-slug.md`.
- Format: **What we expected** → **What actually happened** → **What we
  learned** → **Action items**. Never assign blame; assign actions.
- Link the underlying design fix to its ADR in [`../adr/README.md`](../adr/README.md).

---

## 🧾 Registry

| Date | Subsystem | Severity | Link |
| :--- | :--- | :--- | :--- |
| 2026-09-14 | CI / e2e smoke check | High — masked all CI signal for 3 days | [e2e retrieval never indexed](./2026-09-14-e2e-retrieval-never-indexed.md) |
| 2026-09-14 | Agent flat-file persistence | Critical (latent) — zero-drop spool could not replay | [protojson oneof round-trip](./2026-09-14-protojson-oneof-round-trip.md) |

> [!NOTE]
> Both entries are near-misses caught before production, retroactively
> documented: the protojson round-trip was fixed 2026-08-14 (commit
> `6b7a3ab`) and postmortemed 2026-09-14; the e2e failure was live on CI
> 2026-09-11 → 2026-09-14. Rule of thumb so far: **a silently-wrong
> assertion is worse than a failing test** — it converts signal into noise.