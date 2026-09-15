# Postmortem: protojson Oneof Round-Trip Silently Corrupted Spooled Telemetry

**Date:** 2026-09-14 (incident: 2026-08-14)
**Status:** Resolved

## Incident Summary
While adding unit and integration tests for the agent's flat-file
persistence (commit `6b7a3ab`), a round-trip test revealed that telemetry
persisted with `encoding/json` did not survive a save → load cycle: `oneof`
payloads (metric / syscall / network / anomaly) were serialized with Go's
struct-field naming and could not be unmarshalled back into a valid
`TelemetryRequest`. The spool — the component the zero-drop guarantee rests
on — would have written files it could never replay.

## Root Cause
1. **Wrong serializer for the data shape.** `encoding/json` has no concept
   of protobuf `oneof`: it happily marshals the wrapper struct in a form
   protojson refuses to parse back. The failure is silent at write time —
   the corruption only surfaces on read.
2. **The spool had no round-trip test.** Persist was tested for "file
   exists, non-empty"; nothing asserted load-all == what-was-saved, so the
   bug would have shipped inside the zero-drop story.

## Fix
Spool files switched to a JSON array of **protojson** objects
(`marshalList`/`unmarshalList` in `agent/internal/persistence/flatfile.go`):
each entry marshalled and unmarshalled with protojson so `oneof` payloads
round-trip exactly, with `encoding/json` used only to split/join the array
envelope. A `TestPersistAndLoadAll` regression test pins the invariant.

## Action Items
- [x] Serialize spool entries with protojson; envelope split with
      encoding/json.
- [x] Pin the round-trip with a regression test.
- [ ] Brain-side `BufferStore` uses protoc's `JsonFormat` (the same class
      of bug would apply); its `readWindow` now has a symmetric reader —
      consider a Scala round-trip test to match the Go one.
