# 📜 ADR-010: Zero-Dep Static Dashboard Served by the Brain

> **Status:** `Decided`
> **Date:** `September, 2026`

---

## 🌎 Context

Phase 4 needs a dashboard to visualize retrieval, briefings, and incidents on
top of the existing brain HTTP API. The project's standing constraint is zero
unnecessary dependencies (the brain is one JDK `HttpServer`, the agent ships no
runtime deps, everything else is pluggable defaults). A browser dashboard
fetching `:9091` from a different origin would trip CORS — the existing API
deliberately sets no CORS headers.

## 🛤️ Options Considered

1. **React + Vite SPA in `frontend/`** - _Rich component ecosystem and hot reload, but adds a Node toolchain, a build step, dependency supply-chain surface, and needs a separate serve/CORS story in a zero-dep repo._
2. **Vanilla static HTML/CSS/JS, served by its own static server** - _No build step, but requires a second process and CORS changes to `HttpApi` for cross-origin fetches._
3. **Vanilla static dashboard served by the brain at the same origin** - _No build step, zero new dependencies, one port, no CORS problem — the JDK HttpServer already serves the API and can serve the static files over a `/` context._

## 🎯 Decision

> [!IMPORTANT]  
> We will ship a **zero-dependency vanilla dashboard** in `frontend/` and serve
> it from the brain's own `HttpServer` at `:9091` (same origin) via a `/`
> context, because it keeps the "one JDK server, no toolchain" property and
> sidesteps CORS entirely.

## 🧠 Reasoning

`HttpApi` gained a `webRoot` parameter (default `frontend/`, overridable via
`AEGIS_WEBROOT`) and a root context that maps non-API paths onto static files
with traversal rejection. Because the JDK `HttpServer` matches the longest
prefix, the existing `/api/v1/*` contexts win untouched. The dashboard is three
files (`index.html`, `css/styles.css`, `js/app.js`) with a same-origin `fetch`
and a minimal markdown renderer — no CDNs, no build artifacts, no `node_modules`.
Docker `COPY`s `frontend/` into the image and `scripts/e2e.sh` asserts the
dashboard + assets are served.

## ⚖️ Consequences

- **Good:** 🟢 Zero added runtimes/dependencies; one port to open; e2e covers it; trivially containerized; graceful degradation when the brain is down (status dot turns red).
- **Bad:** 🔴 No component framework, so the UI stays bespoke hand-written markup; vanilla JS has no type checking; when the API surface grows the dashboard must be hand-maintained rather than generated.

## 🔄 Revisit When

When the dashboard needs richer interactions (graphs, streaming live views,
per-metric drill-down) that vanilla JS makes painful — at that point adopt a
framework in `frontend/` (still served by the brain as static `dist/`) or a
dedicated API gateway with CORS. Revisit rendering when the briefing markdown
outgrows the minimal inline renderer.