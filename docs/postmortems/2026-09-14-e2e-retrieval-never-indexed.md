# Postmortem: E2E Smoke Check Failed on Every Run Since Phase 4

**Date:** 2026-09-14
**Status:** Resolved

## Incident Summary
The `Docker Compose E2E smoke check` CI job failed on every push and PR from
the moment the Phase 4 dashboard landed (2026-09-11) through 2026-09-14 —
including runs on `main`. Go and Scala jobs passed every time; only the
smoke check died, always at the same step: `ERROR: retrieval returned no
indexed telemetry`, about 1–5 minutes in. Because the failure was
"chronic green-adjacent", the red X stopped being information.

## Root Cause
1. **The assertion tested an emergent property as if it were a guaranteed
   one.** The retrieval API is fed by the anomaly → flush → index pipeline.
   A *healthy* two-sentinel stack produces almost no anomalies in a short
   CI window, so the vector store legitimately stayed empty. The check
   demanded non-empty retrieval seconds after startup — the system was
   behaving correctly; the test was wrong.
2. **No deterministic trigger existed at the edge.** The agent had no local
   anomaly detection (the proto's `AnomalyEvent` payload had no producer),
   so nothing in the stack could make the pipeline fire on demand.
3. **No recovery path.** When retrieval came back empty, the script exited
   immediately — no wait/retry, and no way to force a reindex of the
   windows the brain *had* persisted.

## Fix
Three layers, so the failure mode is closed structurally rather than
patched:
1. **Agent-side local analytics** (`agent/internal/anomaly`, env-tunable
   thresholds) now emit real `AnomalyEvent`s; compose exposes
   `AEGIS_SMOKE_*` overrides and CI sets them so an anomaly fires within
   seconds. The pipeline trigger is now a test input, not a coincidence.
2. **`scripts/e2e.sh` polls** for indexed telemetry up to `MAX_WAIT` and,
   before failing, calls the new `POST /api/v1/index` reindex fallback —
   a still-empty index is now reported as *exactly* what broke.
3. **Startup reindex** (`StartupReindexer`) rebuilds the in-memory vector
   store from persisted windows, so a restart no longer silently empties
   retrieval.

## Action Items
- [x] Give the agent a deterministic, configurable anomaly trigger
      (ADR-009).
- [x] Make the e2e retrieval check poll + reindex instead of
      assert-instantaneously.
- [x] Rebuild the vector store from persisted windows at startup (ADR-008).
- [ ] Re-check the smoke job after this lands; keep `main` red = loud.
- [ ] Audit remaining e2e assertions for the same "emergent property"
      pattern (briefings/incidents currently only check route liveness).
