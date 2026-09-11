# 🔥 Aegis Postmortems

> **Status:** No incidents documented yet. Infrastructure set up.

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
| _— none yet —_ | | | |

> [!NOTE]
> Aegis is young, so this registry is empty by design. Start it when the
> first real incident or near-miss happens — do not postmortem feature work,
> only failures. Good first candidates from the build so far: the
> protojson round-trip fix in flat-file persistence (6b7a3ea).