"use strict";

const API = "/api/v1";

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));

const el = {
  status: $("#brain-status"),
  incidents: $("#incident-list"),
  incidentCount: $("#incident-count"),
  incidentDetail: $("#incident-detail"),
  briefings: $("#briefing-list"),
  briefingDetail: $("#briefing-detail"),
  retrieve: $("#retrieve-list"),
  agentFilter: $("#agent-filter"),
  searchQuery: $("#search-query"),
  searchTopK: $("#search-topk"),
  autoRefresh: $("#auto-refresh"),
  intervalLabel: $("#interval-label"),
};

const REFRESH_MS = 5000;
let refreshTimer = null;

function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  }[c]));
}

function fmtTime(ns) {
  if (!ns) return "—";
  const d = new Date(Number(ns) / 1e6);
  return d.toLocaleString();
}

function shortTime(ns) {
  if (!ns) return "—";
  return new Date(Number(ns) / 1e6).toLocaleTimeString();
}

function badge(sev) {
  const s = String(sev || "INFO").toUpperCase();
  return `<span class="badge ${s}">${esc(s)}</span>`;
}

async function fetchJSON(path, { ok404 = false } = {}) {
  const res = await fetch(API + path);
  if (res.status === 404 && ok404) return null;
  if (!res.ok) throw new Error(`${res.status} on ${path}`);
  return res.json();
}

function renderMarkdown(md) {
  if (!md) return "";
  const lines = String(md).replace(/\r\n/g, "\n").split("\n");
  const out = [];
  for (const raw of lines) {
    const line = raw.trimEnd();
    const m = line.match(/^(#{1,3})\s+(.*)$/);
    if (m) {
      const lvl = m[1].length;
      out.push(`<h${lvl}>${inline(m[2])}</h${lvl}>`);
      continue;
    }
    if (/^-{3,}$/.test(line)) continue;
    if (/^\s*[-*]\s+/.test(line)) {
      out.push(`<li>${inline(line.replace(/^\s*[-*]\s+/, ""))}</li>`);
      continue;
    }
    if (/^\d+\.\s/.test(line)) {
      out.push(`<li>${inline(line.replace(/^\d+\.\s/, ""))}</li>`);
      continue;
    }
    if (line.trim() === "") {
      if (out.length && out[out.length - 1] === "</ul>") out.push("</ul>");
      continue;
    }
    out.push(`<p>${inline(line)}</p>`);
  }
  let html = out.join("");
  html = html.replace(/(<li>.*<\/li>)(?!.*<\/ul>)/gs, (m) => `<ul>${m}</ul>`);
  return html;
}

function inline(s) {
  return esc(s)
    .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
    .replace(/`([^`]+)`/g, "<code>$1</code>");
}

function setStatus(cls, label) {
  el.status.className = "status " + cls;
  el.status.innerHTML = `<span class="dot"></span>${esc(label)}`;
}

/* ---------- Incidents ---------- */

async function loadIncidents() {
  try {
    const list = await fetchJSON("/incidents");
    if (!Array.isArray(list)) throw new Error("unexpected shape");
    el.incidentCount.textContent = list.length ? `${list.length} incident(s)` : "";
    if (!list.length) {
      el.incidents.innerHTML = `<li class="empty">no incidents</li>`;
      return;
    }
    el.incidents.innerHTML = list
      .slice()
      .reverse()
      .map((inc) => {
        const agents = (inc.agents || []).map(esc).join(", ") || "—";
        return `<li data-inc='${esc(JSON.stringify(inc))}'>
          <div class="item-head">
            <span class="item-title">${badge(inc.maxSeverity)} ${esc(inc.eventType)}</span>
            <span class="item-meta">${shortTime(inc.startNs)} → ${shortTime(inc.endNs)}</span>
          </div>
          <div class="item-meta">agents: ${agents}</div>
        </li>`;
      })
      .join("");
    $$("#incident-list li[data-inc]").forEach((li) =>
      li.addEventListener("click", () => showIncidentDetail(li.dataset.inc))
    );
    showIncidentDetail(el.incidents.querySelector("li[data-inc]")?.dataset.inc);
    setStatus("online", "brain online");
  } catch (err) {
    setStatus("offline", "brain unreachable");
    el.incidents.innerHTML = `<li class="empty">could not reach brain (${esc(err.message)})</li>`;
  }
}

async function showIncidentDetail(json) {
  if (!json) {
    el.incidentDetail.classList.add("hidden");
    return;
  }
  const inc = JSON.parse(json);
  let md = "";
  try {
    const res = await fetchJSON("/incidents/latest", { ok404: true });
    md = res ?? "";
  } catch { md = ""; }
  el.incidentDetail.classList.remove("hidden");
  el.incidentDetail.innerHTML = md
    ? renderMarkdown(md)
    : `<div class="empty">no incident briefing yet</div>`;
}

/* ---------- Briefings ---------- */

async function loadBriefings() {
  const agent = el.agentFilter.value.trim();
  const q = agent ? `?agent_id=${encodeURIComponent(agent)}` : "";
  try {
    const list = await fetchJSON("/briefings" + q);
    const arr = Array.isArray(list) ? list : [];
    if (arr.length) {
      el.briefings.innerHTML = arr
        .slice()
        .reverse()
        .map((b) => `<li data-agent='${esc(b.agentId)}'>
            <div class="item-head">
              <span class="item-title">${badge(b.severity)} ${esc(b.anomalyType)}</span>
              <span class="item-meta">agent ${esc(b.agentId)}</span>
            </div>
            <div class="item-meta">v${b.version} · score ${Number(b.score).toFixed(3)} · ${shortTime(b.timestampNs)}</div>
          </li>`)
        .join("");
      $$("#briefing-list li[data-agent]").forEach((li) =>
        li.addEventListener("click", () => showLatestBriefing(li.dataset.agent))
      );
    } else {
      el.briefings.innerHTML = `<li class="empty">no briefings${agent ? ` for ${esc(agent)}` : ""}</li>`;
    }
    showLatestBriefing(agent, true);
  } catch (err) {
    el.briefings.innerHTML = `<li class="empty">failed: ${esc(err.message)}</li>`;
  }
}

async function showLatestBriefing(agent, force = false) {
  if (!force && !agent) return;
  const q = agent ? `?agent_id=${encodeURIComponent(agent)}` : "";
  try {
    const md = await fetchJSON(`/briefings/latest${q}`, { ok404: true });
    el.briefingDetail.innerHTML = md
      ? renderMarkdown(md)
      : `<div class="empty">no briefing yet${agent ? ` for ${esc(agent)}` : ""}</div>`;
  } catch (err) {
    el.briefingDetail.innerHTML = `<div class="empty">failed: ${esc(err.message)}</div>`;
  }
}

/* ---------- Retrieve ---------- */

async function search() {
  const q = el.searchQuery.value.trim();
  if (!q) return;
  const topK = Math.min(50, Math.max(1, parseInt(el.searchTopK.value, 10) || 5));
  try {
    const res = await fetch(`/api/v1/retrieve?q=${encodeURIComponent(q)}&top_k=${topK}`);
    if (!res.ok) throw new Error(`${res.status} on retrieve`);
    const hits = await res.json();
    if (!hits.length) {
      el.retrieve.innerHTML = `<li class="empty">no indexed telemetry matched "${esc(q)}"</li>`;
      return;
    }
    el.retrieve.innerHTML = hits
      .map((h) => {
        const meta = Object.entries(h.metadata || {})
          .map(([k, v]) => `${esc(k)}=${esc(v)}`)
          .join(" · ");
        return `<li>
          <div class="item-head">
            <span class="item-title">${esc(h.text)}</span>
            <span class="score">${Number(h.score).toFixed(3)}</span>
          </div>
          <div class="item-meta">${meta}</div>
        </li>`;
      })
      .join("");
  } catch (err) {
    el.retrieve.innerHTML = `<li class="empty">failed: ${esc(err.message)}</li>`;
  }
}

/* ---------- wiring ---------- */

function scheduleRefresh() {
  clearInterval(refreshTimer);
  if (el.autoRefresh.checked) {
    refreshTimer = setInterval(async () => {
      await loadIncidents();
      await loadBriefings();
    }, REFRESH_MS);
    el.intervalLabel.textContent = `${REFRESH_MS / 1000}s`;
  } else {
    el.intervalLabel.textContent = "off";
  }
}

el.autoRefresh.addEventListener("change", scheduleRefresh);
$("#reload-briefings").addEventListener("click", loadBriefings);
$("#search-btn").addEventListener("click", search);
el.searchQuery.addEventListener("keydown", (e) => { if (e.key === "Enter") search(); });
el.agentFilter.addEventListener("keydown", (e) => { if (e.key === "Enter") loadBriefings(); });

(async function boot() {
  scheduleRefresh();
  await Promise.all([loadIncidents(), loadBriefings()]);
})();