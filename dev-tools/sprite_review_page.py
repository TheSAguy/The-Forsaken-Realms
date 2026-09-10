#!/usr/bin/env python
"""
sprite_review_page.py - turn sprite_sizes.py's --json output into the size-class review page.

    python dev-tools/sprite_sizes.py --json review.json
    python dev-tools/sprite_review_page.py review.json out.html

The page shows every in-scope enemy at its current size and at the size the class rule proposes,
side by side on a tile grid, with a class picker per row. Decisions are written to the artifact's
database (collection "overrides", one document per enemy) when the page runs inside claude.ai,
and to localStorage plus a copyable text block otherwise. Absence of an override = the proposal
is accepted.
"""
import json, sys, html

TEMPLATE = r"""<title>Sprite Size Classes</title>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Pixelify+Sans:wght@500;700&family=Karla:wght@400;500;700&family=JetBrains+Mono:wght@400;600&display=swap">
<style>
:root {
  --bg: #f3f4f7; --panel: #ffffff; --panel-2: #eceef3; --line: #d8dce5; --ink: #1b1f27; --muted: #5f6878;
  --accent: #0f9d95; --accent-ink: #ffffff; --eyes: #c98a12; --eyes-bg: #fff6e3;
  --checker-a: #e9ebf0; --checker-b: #dfe2e9; --tile: #b9bfcc;
  --common: #6b7482; --uncommon: #4f6f96; --rare: #b3861f; --mythic: #d2561f;
  --font-display: "Pixelify Sans", "Karla", sans-serif;
  --font-body: "Karla", "Segoe UI", system-ui, sans-serif;
  --font-mono: "JetBrains Mono", "Cascadia Mono", Consolas, monospace;
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    --bg: #14171d; --panel: #1b1f27; --panel-2: #222733; --line: #2c3342; --ink: #e7e9ef; --muted: #8c94a6;
    --accent: #4fd1c5; --accent-ink: #0f1419; --eyes: #f0b429; --eyes-bg: #2a2416;
    --checker-a: #1f242e; --checker-b: #262c38; --tile: #3a4356;
    --common: #9aa3b2; --uncommon: #9fb8d6; --rare: #e2b24f; --mythic: #f07b3f;
  }
}
:root[data-theme="dark"] {
  --bg: #14171d; --panel: #1b1f27; --panel-2: #222733; --line: #2c3342; --ink: #e7e9ef; --muted: #8c94a6;
  --accent: #4fd1c5; --accent-ink: #0f1419; --eyes: #f0b429; --eyes-bg: #2a2416;
  --checker-a: #1f242e; --checker-b: #262c38; --tile: #3a4356;
  --common: #9aa3b2; --uncommon: #9fb8d6; --rare: #e2b24f; --mythic: #f07b3f;
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--ink); font-family: var(--font-body); font-size: 14px; line-height: 1.45; }
button, select, input { font: inherit; color: inherit; }
button:focus-visible, select:focus-visible, input:focus-visible, summary:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }

header { position: sticky; top: 0; z-index: 5; background: var(--panel); border-bottom: 1px solid var(--line); }
.bar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px 20px; padding: 12px 20px; max-width: 1400px; margin: 0 auto; }
h1 { font-family: var(--font-display); font-weight: 700; font-size: 26px; margin: 0; letter-spacing: 0.01em; }
.rule { color: var(--muted); max-width: 62ch; text-wrap: balance; margin: 0; }
.stats { display: flex; gap: 18px; margin-left: auto; font-variant-numeric: tabular-nums; }
.stat { display: flex; flex-direction: column; gap: 1px; }
.stat b { font-family: var(--font-mono); font-size: 18px; font-weight: 600; line-height: 1.1; }
.stat span { font-size: 11px; letter-spacing: 0.06em; text-transform: uppercase; color: var(--muted); }
.controls { display: flex; flex-wrap: wrap; gap: 8px 14px; align-items: center; padding: 0 20px 12px; max-width: 1400px; margin: 0 auto; }
.chips { display: flex; flex-wrap: wrap; gap: 6px; }
.chip { border: 1px solid var(--line); background: var(--panel-2); border-radius: 999px; padding: 3px 10px; cursor: pointer; font-size: 13px; }
.chip[aria-pressed="true"] { background: var(--accent); color: var(--accent-ink); border-color: var(--accent); }
.controls label { display: flex; align-items: center; gap: 6px; color: var(--muted); font-size: 13px; }
.controls input[type="search"] { border: 1px solid var(--line); background: var(--panel-2); border-radius: 6px; padding: 5px 9px; min-width: 200px; }
.controls select { border: 1px solid var(--line); background: var(--panel-2); border-radius: 6px; padding: 4px 8px; }

main { max-width: 1400px; margin: 0 auto; padding: 16px 20px 140px; }
section.cls { margin-top: 26px; }
section.cls > h2 { font-family: var(--font-display); font-weight: 500; font-size: 22px; margin: 0 0 4px; display: flex; align-items: baseline; gap: 14px; }
section.cls > h2 small { font-family: var(--font-mono); font-size: 13px; color: var(--muted); font-weight: 400; }
.ruler { height: 10px; margin-bottom: 10px; background: repeating-linear-gradient(90deg, var(--tile) 0 1px, transparent 1px 16px); width: 100%; max-width: 480px; opacity: .8; }
.rows { display: grid; gap: 6px; }
.row { display: grid; grid-template-columns: minmax(150px, auto) minmax(220px, 1.4fr) 250px minmax(200px, 1.2fr) 150px; gap: 14px; align-items: end;
       background: var(--panel); border: 1px solid var(--line); border-left: 4px solid var(--line); border-radius: 6px; padding: 8px 12px 8px 10px; }
.row.eyes { border-left-color: var(--eyes); }
.row.over { border-left-color: var(--accent); }
.row[hidden] { display: none; }
.pair { display: flex; align-items: flex-end; gap: 10px; }
.stage { position: relative; display: flex; align-items: flex-end; justify-content: center; min-width: 48px; padding: 0 6px;
         background-color: var(--checker-a);
         background-image: linear-gradient(45deg, var(--checker-b) 25%, transparent 25%, transparent 75%, var(--checker-b) 75%),
                           linear-gradient(45deg, var(--checker-b) 25%, transparent 25%, transparent 75%, var(--checker-b) 75%);
         background-size: 8px 8px; background-position: 0 0, 4px 4px; border-bottom: 1px solid var(--tile); }
.stage::after { content: ""; position: absolute; left: 0; right: 0; bottom: 0; top: 0; pointer-events: none;
                background: repeating-linear-gradient(0deg, transparent 0 calc(var(--zoom) * 16px - 1px), var(--tile) calc(var(--zoom) * 16px - 1px) calc(var(--zoom) * 16px)); opacity: .55; }
.stage img { image-rendering: pixelated; image-rendering: crisp-edges; display: block; position: relative; z-index: 1; }
.arrow { color: var(--muted); align-self: center; font-size: 18px; }
.who { display: flex; flex-direction: column; gap: 3px; min-width: 0; align-self: center; }
.who .name { font-weight: 700; font-size: 15px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.tier { font-size: 11px; letter-spacing: 0.05em; text-transform: uppercase; padding: 1px 7px; border-radius: 3px; border: 1px solid currentColor; }
.tier.Common { color: var(--common); } .tier.Uncommon { color: var(--uncommon); } .tier.Rare { color: var(--rare); } .tier.Mythic { color: var(--mythic); }
.who .path { color: var(--muted); font-size: 12px; font-family: var(--font-mono); overflow-wrap: anywhere; }
.who .tags { color: var(--muted); font-size: 12px; }
.nums { font-family: var(--font-mono); font-size: 12px; font-variant-numeric: tabular-nums; display: grid; grid-template-columns: auto auto; gap: 2px 10px; align-self: center; color: var(--muted); }
.nums b { color: var(--ink); font-weight: 600; }
.why { font-size: 13px; color: var(--muted); align-self: center; }
.why .flag { color: var(--eyes); font-weight: 700; }
.pick { display: flex; flex-direction: column; gap: 6px; align-self: center; }
.pick select { width: 100%; border: 1px solid var(--line); background: var(--panel-2); border-radius: 6px; padding: 5px 8px; }
.pick .note { font-size: 11px; color: var(--accent); min-height: 14px; }

.drawer { position: fixed; left: 0; right: 0; bottom: 0; z-index: 6; background: var(--panel); border-top: 1px solid var(--line); }
.drawer .in { max-width: 1400px; margin: 0 auto; padding: 10px 20px; display: grid; grid-template-columns: auto 1fr auto; gap: 8px 20px; align-items: center; }
.drawer h3 { margin: 0; font-family: var(--font-display); font-weight: 500; font-size: 18px; }
.drawer .list { font-family: var(--font-mono); font-size: 12px; max-height: 88px; overflow: auto; color: var(--muted); display: flex; flex-wrap: wrap; gap: 4px 14px; }
.drawer .list b { color: var(--ink); font-weight: 600; }
.drawer .save { font-size: 12px; color: var(--muted); text-align: right; }
.drawer .save b { color: var(--accent); }
details.kept { margin-top: 40px; color: var(--muted); }
details.kept summary { cursor: pointer; font-weight: 700; color: var(--ink); }
details.kept p { max-width: 90ch; }
.empty { color: var(--muted); padding: 30px 0; }
@media (max-width: 900px) {
  .row { grid-template-columns: 1fr 1fr; }
  .pair { grid-column: 1 / -1; }
  .nums, .why, .pick { grid-column: span 1; }
}
@media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>

<header>
  <div class="bar">
    <h1>Sprite Size Classes</h1>
    <p class="rule">Rendered height follows what a creature <em>is</em>, not how big its art happens to be. Six classes in tiles: Tiny 8 · Critter 12 · Person 16 · Medium 24 · Large 32 · Huge 48, all before the rank cue. Scale = class height ÷ frame height.</p>
    <div class="stats">
      <div class="stat"><b id="stInScope">0</b><span>in scope</span></div>
      <div class="stat"><b id="stEyes">0</b><span>need eyes</span></div>
      <div class="stat"><b id="stOver">0</b><span>overrides</span></div>
      <div class="stat"><b id="stShown">0</b><span>shown</span></div>
    </div>
  </div>
  <div class="controls">
    <div class="chips" id="classChips"></div>
    <label><input type="checkbox" id="eyesOnly"> needs eyes only</label>
    <label><input type="checkbox" id="overOnly"> overridden only</label>
    <label>rank <select id="tierSel"><option value="">all</option><option>Common</option><option>Uncommon</option><option>Rare</option><option>Mythic</option></select></label>
    <label>pack <select id="packSel"><option value="">all</option><option value="plane">plane</option><option value="common">common</option></select></label>
    <label>zoom <select id="zoomSel"><option>2</option><option selected>3</option><option>4</option></select></label>
    <input type="search" id="q" placeholder="search name, atlas, tag">
  </div>
</header>

<main id="main"></main>

<div class="drawer">
  <div class="in">
    <h3>Decisions</h3>
    <div class="list" id="decisions"><span>No overrides yet. Every row without one takes the proposed class.</span></div>
    <div class="save" id="saveState">checking storage…</div>
  </div>
</div>

<script id="data" type="application/json">__DATA__</script>
<script>
(function () {
  const DATA = JSON.parse(document.getElementById("data").textContent);
  const CLASSES = DATA.rule.classes;            // [[name, px], ...]
  const CLASS_PX = Object.fromEntries(CLASSES);
  const TIER_CUE = { Common: 0.9, Uncommon: 1.0, Rare: 1.1, Mythic: 1.25 };
  const TIER_NAME = { Common: "Apprentice", Uncommon: "Adept", Rare: "Master", Mythic: "Archmage" };
  const rows = DATA.rows;
  const overrides = {};                        // name -> class
  let db = null, zoom = 3;
  const state = { cls: "", eyes: false, over: false, tier: "", pack: "", q: "" };

  // Tile-anchored rank cue, the same formula as TuningData.tierSizeMultiplier.
  function withCue(px, tier) {
    const cue = (TIER_CUE[tier] || 1) - 1;
    if (!cue || px <= 0) return px;
    return px <= 16 ? px * (1 + cue) : px * (1 + cue * 16 / px);
  }
  function slug(name) {
    let s = name.replace(/[^A-Za-z0-9_\-.~:@+]+/g, "_").slice(0, 60);
    let h = 0; for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
    return s + "-" + h.toString(36);
  }
  function effective(r) { return overrides[r.name] || r.cls; }
  function fmt(n) { return (Math.round(n * 10) / 10).toString(); }

  // ---------------------------------------------------------------- build
  const main = document.getElementById("main");
  const els = new Map();
  function build() {
    main.innerHTML = "";
    for (const [cls, px] of CLASSES) {
      const sec = document.createElement("section");
      sec.className = "cls"; sec.dataset.cls = cls;
      sec.innerHTML = `<h2>${cls} <small>${px}px · ${(px / 16).toString().replace(/^0/, "")} tile${px === 16 ? "" : "s"}</small></h2><div class="ruler"></div><div class="rows"></div><div class="empty" hidden>Nothing here with the current filters.</div>`;
      main.appendChild(sec);
    }
    for (const r of rows) {
      const row = document.createElement("article");
      row.className = "row";
      row.dataset.name = r.name;
      const opts = CLASSES.map(([c, p]) => `<option value="${c}">${c} ${p}px</option>`).join("");
      row.innerHTML = `
        <div class="pair">
          <div class="stage cur"><img alt="" src="${r.thumb}"></div>
          <div class="arrow" aria-hidden="true">→</div>
          <div class="stage prop"><img alt="" src="${r.thumb}"></div>
        </div>
        <div class="who">
          <div class="name"><span>${esc(r.name)}</span><span class="tier ${r.tier}">${TIER_NAME[r.tier] || r.tier}</span>${r.boss ? '<span class="tier">boss</span>' : ""}</div>
          <div class="path">${esc(r.sprite.replace(/^sprites\/enemy\//, ""))} · ${r.where}</div>
          <div class="tags">${esc(r.tags.slice(0, 5).join(", "))}${r.life ? " · life " + r.life : ""}</div>
        </div>
        <div class="nums">
          <span>frame</span><b>${r.w}×${r.h}</b>
          <span>scale</span><b>${r.scale} → <span class="pscale"></span></b>
          <span>height</span><b>${fmt(r.cur)} → <span class="ppx"></span> px</b>
          <span>with cue</span><b><span class="pcue"></span> px</b>
        </div>
        <div class="why">${r.eyes ? '<span class="flag">needs eyes · </span>' : ""}${esc(r.reason)}</div>
        <div class="pick"><select aria-label="class for ${esc(r.name)}">${opts}</select><div class="note"></div></div>`;
      row.querySelector("select").addEventListener("change", (e) => setOverride(r, e.target.value));
      main.querySelector(`section[data-cls="${r.cls}"] .rows`).appendChild(row);
      els.set(r.name, row);
      paint(r);
    }
    applyFilters();
  }
  function paint(r) {
    const row = els.get(r.name);
    const cls = effective(r);
    const px = CLASS_PX[cls];
    const pscale = Math.round(px / r.h * 100) / 100;
    const ppx = r.h * pscale;
    row.querySelector(".pscale").textContent = pscale;
    row.querySelector(".ppx").textContent = fmt(ppx);
    row.querySelector(".pcue").textContent = fmt(withCue(ppx, r.tier));
    row.querySelector("select").value = cls;
    row.querySelector(".note").textContent = overrides[r.name] ? "overridden (proposed " + r.cls + ")" : "";
    row.classList.toggle("over", !!overrides[r.name]);
    row.classList.toggle("eyes", r.eyes && !overrides[r.name]);
    const cur = row.querySelector(".stage.cur img"), prop = row.querySelector(".stage.prop img");
    cur.style.width = (r.w * r.scale * zoom) + "px"; cur.style.height = (r.h * r.scale * zoom) + "px";
    prop.style.width = (r.w * pscale * zoom) + "px"; prop.style.height = (ppx * zoom) + "px";
    cur.title = fmt(r.cur) + "px now"; prop.title = fmt(ppx) + "px as " + cls;
    // Move the row under the section of its effective class so the page reads as the outcome.
    const target = main.querySelector(`section[data-cls="${cls}"] .rows`);
    if (row.parentElement !== target) target.appendChild(row);
  }
  function esc(s) { return String(s).replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c])); }

  // ---------------------------------------------------------------- filters
  function applyFilters() {
    let shown = 0;
    const q = state.q.trim().toLowerCase();
    for (const r of rows) {
      const row = els.get(r.name);
      const cls = effective(r);
      let ok = true;
      if (state.cls && cls !== state.cls) ok = false;
      if (state.eyes && !r.eyes) ok = false;
      if (state.over && !overrides[r.name]) ok = false;
      if (state.tier && r.tier !== state.tier) ok = false;
      if (state.pack && r.where !== state.pack) ok = false;
      if (q && !(r.name.toLowerCase().includes(q) || r.sprite.toLowerCase().includes(q) || r.tags.join(" ").toLowerCase().includes(q) || r.reason.toLowerCase().includes(q))) ok = false;
      row.hidden = !ok;
      if (ok) shown++;
    }
    for (const sec of main.querySelectorAll("section.cls")) {
      const any = [...sec.querySelectorAll(".row")].some(x => !x.hidden);
      sec.querySelector(".empty").hidden = any;
      sec.hidden = !!state.cls && sec.dataset.cls !== state.cls;
    }
    document.getElementById("stShown").textContent = shown;
    document.getElementById("stInScope").textContent = rows.length;
    document.getElementById("stEyes").textContent = rows.filter(r => r.eyes).length;
    document.getElementById("stOver").textContent = Object.keys(overrides).length;
    renderDecisions();
  }
  const chips = document.getElementById("classChips");
  for (const label of ["all", ...CLASSES.map(c => c[0])]) {
    const b = document.createElement("button");
    b.className = "chip"; b.textContent = label; b.setAttribute("aria-pressed", label === "all");
    b.addEventListener("click", () => {
      state.cls = label === "all" ? "" : label;
      for (const c of chips.children) c.setAttribute("aria-pressed", c === b);
      applyFilters();
    });
    chips.appendChild(b);
  }
  document.getElementById("eyesOnly").addEventListener("change", e => { state.eyes = e.target.checked; applyFilters(); });
  document.getElementById("overOnly").addEventListener("change", e => { state.over = e.target.checked; applyFilters(); });
  document.getElementById("tierSel").addEventListener("change", e => { state.tier = e.target.value; applyFilters(); });
  document.getElementById("packSel").addEventListener("change", e => { state.pack = e.target.value; applyFilters(); });
  document.getElementById("q").addEventListener("input", e => { state.q = e.target.value; applyFilters(); });
  document.getElementById("zoomSel").addEventListener("change", e => { zoom = +e.target.value; document.documentElement.style.setProperty("--zoom", zoom); rows.forEach(paint); });
  document.documentElement.style.setProperty("--zoom", zoom);

  // ---------------------------------------------------------------- decisions + storage
  const saveState = document.getElementById("saveState");
  function renderDecisions() {
    const box = document.getElementById("decisions");
    const names = Object.keys(overrides).sort();
    if (!names.length) { box.innerHTML = "<span>No overrides yet. Every row without one takes the proposed class.</span>"; return; }
    box.innerHTML = names.map(n => `<span><b>${esc(n)}</b> = ${overrides[n]}</span>`).join("");
  }
  function setOverride(r, cls) {
    if (cls === r.cls) delete overrides[r.name]; else overrides[r.name] = cls;
    paint(r); applyFilters();
    persist(r, cls === r.cls ? null : cls);
  }
  function persist(r, cls) {
    if (db) {
      const ref = db.doc("overrides/" + slug(r.name));
      const p = cls ? ref.set({ name: r.name, cls: cls, proposed: r.cls, at: new Date().toISOString() }) : ref.delete();
      p.then(() => { saveState.innerHTML = "<b>saved</b> to the shared record"; })
       .catch(e => { saveState.textContent = "could not save (" + (e && e.code || "error") + ") - it is still in the text below"; });
    } else {
      try { localStorage.setItem("tfr-sprite-overrides", JSON.stringify(overrides)); saveState.textContent = "saved in this browser only - copy the decisions to hand them over"; } catch (_) { saveState.textContent = "not saved - copy the decisions to hand them over"; }
    }
  }
  function hydrateLocal() {
    try {
      const saved = JSON.parse(localStorage.getItem("tfr-sprite-overrides") || "{}");
      for (const [n, c] of Object.entries(saved)) if (CLASS_PX[c] && els.has(n)) overrides[n] = c;
    } catch (_) {}
    rows.forEach(paint); applyFilters();
    saveState.textContent = "this browser only - copy the decisions to hand them over";
  }
  function connect() {
    const use = window.claude && typeof window.claude.use === "function" ? window.claude.use("db") : Promise.resolve(null);
    use.then(ns => {
      if (!ns) return hydrateLocal();
      db = ns;
      db.collection("overrides").onSnapshot(snap => {
        for (const k of Object.keys(overrides)) delete overrides[k];
        for (const d of snap.docs) { const b = d.data(); if (b && CLASS_PX[b.cls] && els.has(b.name)) overrides[b.name] = b.cls; }
        rows.forEach(paint); applyFilters();
        saveState.innerHTML = snap.metadata.fromCache ? "loading the shared record…" : "<b>shared record</b> · " + snap.size + " saved";
      }, err => { db = null; hydrateLocal(); saveState.textContent = "shared record unavailable (" + (err && err.code || "error") + ")"; });
    }).catch(() => hydrateLocal());
  }

  build();
  connect();
})();
</script>
"""


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    data = json.load(open(sys.argv[1], encoding="utf-8"))
    payload = json.dumps(data, separators=(",", ":")).replace("</", "<\\/")
    out = TEMPLATE.replace("__DATA__", payload)
    with open(sys.argv[2], "w", encoding="utf-8") as f:
        f.write(out)
    print("wrote %s (%d rows, %.1f MB)" % (sys.argv[2], len(data["rows"]), len(out) / 1e6))


if __name__ == "__main__":
    main()
