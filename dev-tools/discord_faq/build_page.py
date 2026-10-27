"""Build the Discord FAQ copy-helper page from messages.py. usage: build_page.py <out.html>"""
import json
import sys

sys.path.insert(0, __file__.rsplit("\\", 1)[0] if "\\" in __file__ else __file__.rsplit("/", 1)[0])
from messages import ADDED, EDITED, FAQ_VERSION, M  # noqa: E402

data = {"messages": [m[2] for m in M], "topics": [m[0] for m in M], "short": [m[1] for m in M],
        "edited": sorted(EDITED), "added": sorted(ADDED)}
for t in data["messages"]:
    assert len(t) < 2000, len(t)
n = len(M)
help_msg = next(i for i, m in enumerate(M, 1) if "#how-can-i-help" in m[2])
blob = json.dumps(data, ensure_ascii=False).replace("</", "<\\/")

PAGE = r"""<title>Forsaken Realms FAQ</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Cinzel:wght@600&family=Source+Sans+3:ital,wght@0,400;0,600;0,700;1,400&family=JetBrains+Mono:wght@400;600&display=swap">
<style>
:root {
  --bg: #eceef3; --surface: #ffffff; --chat: #ffffff; --ink: #1e2230; --muted: #5a6173; --line: #d8dce5;
  --accent: #4a55c8; --accent-ink: #ffffff; --accent-soft: #e6e8fb; --ok: #1d7543; --ok-soft: #dff1e6;
  --warn: #7d5200; --warn-soft: #f8ecd2; --code-bg: #f0f1f4; --code-ink: #2c3036; --code-line: #dfe2e8;
  --track: #e2e5ec; --fill: #4a55c8; --focus: #4a55c8;
  --display: "Cinzel", "Trajan Pro", Georgia, serif;
  --body: "Source Sans 3", "Segoe UI", system-ui, sans-serif;
  --mono: "JetBrains Mono", Consolas, "Courier New", monospace;
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    color-scheme: dark;
    --bg: #18191d; --surface: #23252a; --chat: #2c2e34; --ink: #eceef2; --muted: #a6acb9; --line: #373a41;
    --accent: #5561e0; --accent-ink: #ffffff; --accent-soft: #2c3050; --ok: #62c889; --ok-soft: #1e3629;
    --warn: #eec26a; --warn-soft: #3a311f; --code-bg: #1e2024; --code-ink: #dcdfe5; --code-line: #33363c;
    --track: #33363d; --fill: #8189f2; --focus: #9aa1f5;
  }
}
:root[data-theme="dark"] {
  color-scheme: dark;
  --bg: #18191d; --surface: #23252a; --chat: #2c2e34; --ink: #eceef2; --muted: #a6acb9; --line: #373a41;
  --accent: #5561e0; --accent-ink: #ffffff; --accent-soft: #2c3050; --ok: #62c889; --ok-soft: #1e3629;
  --warn: #eec26a; --warn-soft: #3a311f; --code-bg: #1e2024; --code-ink: #dcdfe5; --code-line: #33363c;
  --track: #33363d; --fill: #8189f2; --focus: #9aa1f5;
}
* { box-sizing: border-box; }
html { scroll-behavior: smooth; }
@media (prefers-reduced-motion: reduce) { html { scroll-behavior: auto; } }
body { margin: 0; background: var(--bg); color: var(--ink); font: 16px/1.5 var(--body); }
.wrap { max-width: 820px; margin: 0 auto; padding-inline: 16px; padding-block: 32px 64px; display: grid; gap: 28px; }
.top { display: grid; gap: 14px; }
.eyebrow { margin: 0; font: 600 12px/1.2 var(--mono); letter-spacing: 0.08em; text-transform: uppercase; color: var(--muted); }
h1 { margin: 0; font: 600 clamp(28px, 6vw, 40px)/1.1 var(--display); letter-spacing: 0.02em; text-wrap: balance; }
.lede { margin: 0; max-width: 62ch; color: var(--muted); }
.chips { display: flex; flex-wrap: wrap; gap: 8px; }
.chip { display: inline-flex; align-items: center; gap: 8px; padding: 6px 12px 6px 6px; border: 1px solid var(--line);
  border-radius: 999px; background: var(--surface); color: var(--ink); font: 600 14px/1 var(--body); text-decoration: none; }
.chip .n { display: inline-grid; place-items: center; width: 24px; height: 24px; border-radius: 50%; background: var(--track);
  font: 600 12px/1 var(--mono); font-variant-numeric: tabular-nums; }
.chip.done { border-color: var(--ok); background: var(--ok-soft); }
.chip.done .n { background: var(--ok); color: var(--surface); }
.chip:hover { border-color: var(--accent); }
.status { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
#progress { font-weight: 600; font-variant-numeric: tabular-nums; }
.notes { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.notes li { padding: 10px 14px; border-radius: 8px; background: var(--surface); border: 1px solid var(--line); color: var(--muted); font-size: 15px; }
.notes strong { color: var(--ink); }
.tag { font-size: 12px; font-weight: 700; letter-spacing: .04em; text-transform: uppercase; padding: 2px 8px;
  border-radius: 999px; background: var(--warn-soft); color: var(--warn); }
.tag.added { background: var(--accent-soft); color: var(--accent); }
.chip .tag { font-size: 10px; padding: 1px 6px; }
button { font: 600 14px/1 var(--body); border-radius: 6px; cursor: pointer; padding: 9px 14px; }
button:focus-visible, .chip:focus-visible, summary:focus-visible, textarea:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
.ghost { background: transparent; color: var(--muted); border: 1px solid var(--line); }
.ghost:hover { color: var(--ink); border-color: var(--muted); }
main { display: grid; gap: 20px; }
.msg { background: var(--surface); border: 1px solid var(--line); border-radius: 10px; display: grid; gap: 12px; padding: 16px; scroll-margin-top: 16px; }
.msg.done { border-color: var(--ok); }
.msg-head { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 12px; }
.msg-head .n { display: inline-grid; place-items: center; width: 32px; height: 32px; border-radius: 50%; background: var(--accent-soft);
  color: var(--accent); font: 600 14px/1 var(--mono); font-variant-numeric: tabular-nums; }
.msg.done .msg-head .n { background: var(--ok); color: var(--surface); }
.msg-head h2 { margin: 0; font: 700 18px/1.25 var(--body); flex: 1 1 200px; text-wrap: balance; }
.copy { background: var(--accent); color: var(--accent-ink); border: 1px solid var(--accent); min-width: 104px; }
.copy:hover { filter: brightness(1.08); }
.msg.done .copy { background: var(--ok-soft); color: var(--ok); border-color: var(--ok); }
.meter { display: flex; align-items: center; gap: 10px; }
.meter .track { flex: 1; height: 6px; border-radius: 3px; background: var(--track); overflow: hidden; }
.meter .fill { height: 100%; background: var(--fill); border-radius: 3px; }
.meter .count { font: 400 12px/1 var(--mono); color: var(--muted); font-variant-numeric: tabular-nums; white-space: nowrap; }
.preview { background: var(--chat); border: 1px solid var(--line); border-radius: 8px; padding: 14px 16px; font-size: 15px; line-height: 1.4; overflow-wrap: anywhere; }
.preview .ln { min-height: 1.4em; }
.preview .gap { height: 0.6em; }
.preview .li { position: relative; padding-left: 22px; }
.preview .li::before { content: "\2022"; position: absolute; left: 7px; color: var(--muted); }
.preview .li.l2 { padding-left: 44px; }
.preview .li.l2::before { left: 29px; content: "\25E6"; }
.preview .ol { padding-left: 22px; text-indent: -22px; }
.preview .ol .num { display: inline-block; width: 22px; text-indent: 0; color: var(--muted); }
.preview code { font: 13px/1.3 var(--mono); background: var(--code-bg); border-radius: 3px; padding: 1px 4px; }
.preview pre { margin: 4px 0; padding: 10px 12px; background: var(--code-bg); color: var(--code-ink); border: 1px solid var(--code-line);
  border-radius: 4px; font: 13px/1.45 var(--mono); overflow-x: auto; white-space: pre; }
.preview strong { font-weight: 700; }
details.raw summary { cursor: pointer; color: var(--muted); font-size: 14px; width: fit-content; }
details.raw textarea { margin-top: 8px; width: 100%; resize: vertical; background: var(--code-bg); color: var(--code-ink);
  border: 1px solid var(--code-line); border-radius: 6px; padding: 10px; font: 12.5px/1.45 var(--mono); }
.hint { margin: 0; font-size: 14px; color: var(--warn); }
@media (max-width: 520px) { .msg { padding: 14px 12px; } .copy { flex: 1 1 100%; } }
</style>

<div class="wrap">
  <header class="top">
    <p class="eyebrow">Discord #faq &middot; __VERSION__</p>
    <h1>Forsaken Realms FAQ</h1>
    <p class="lede">__COUNT_WORD__ messages in posting order, each under Discord's 2,000-character limit. Copy one, paste it into the channel, send it, then copy the next.</p>
    <nav class="chips" id="chips" aria-label="Messages"></nav>
    <div class="status">
      <span id="progress" aria-live="polite">0 of __N__ copied</span>
      <button type="button" class="ghost" id="reset">Clear marks</button>
    </div>
    <ul class="notes">
      __CHANGES__
      <li><strong>Channel link.</strong> #how-can-i-help in message __HELP__ links only if Discord recognizes it when you send. If it stays plain text, retype it and pick the channel from the list.</li>
    </ul>
  </header>
  <main id="list"></main>
</div>

<script type="application/json" id="faq-data">__DATA__</script>
<script>
(function () {
  var data = JSON.parse(document.getElementById("faq-data").textContent);
  var msgs = data.messages, topics = data.topics, shortTopics = data.short;
  var LIMIT = 2000, KEY = "tfr-faq-copied-__VERSION__";
  var edited = {}, added = {};
  (data.edited || []).forEach(function (n) { edited[n] = true; });
  (data.added || []).forEach(function (n) { added[n] = true; });
  function tag(n) { return added[n] ? ' <span class="tag added">New</span>' : edited[n] ? ' <span class="tag">Edit</span>' : ""; }
  var copied = {};
  try { (JSON.parse(localStorage.getItem(KEY) || "[]") || []).forEach(function (n) { copied[n] = true; }); } catch (e) {}
  function save() { try { localStorage.setItem(KEY, JSON.stringify(Object.keys(copied).map(Number))); } catch (e) {} }
  function esc(s) { return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;"); }
  function inline(s) {
    return s.split(/(`[^`]+`)/).map(function (part) {
      if (/^`[^`]+`$/.test(part)) return "<code>" + esc(part.slice(1, -1)) + "</code>";
      var h = esc(part);
      h = h.replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>");
      h = h.replace(/\*(?!\s)(.+?)\*/g, "<em>$1</em>");
      return h;
    }).join("");
  }
  function render(md) {
    var out = [], lines = md.split("\n"), i = 0;
    while (i < lines.length) {
      var line = lines[i];
      if (/^```/.test(line)) {
        var buf = [];
        i++;
        while (i < lines.length && !/^```/.test(lines[i])) { buf.push(lines[i]); i++; }
        i++;
        out.push("<pre>" + esc(buf.join("\n")) + "</pre>");
        continue;
      }
      var m;
      if (line.trim() === "") out.push('<div class="gap"></div>');
      else if ((m = /^(\s*)- (.*)$/.exec(line))) out.push('<div class="li' + (m[1].length >= 2 ? " l2" : "") + '">' + inline(m[2]) + "</div>");
      else if ((m = /^(\d+)\. (.*)$/.exec(line))) out.push('<div class="ol"><span class="num">' + m[1] + ".</span>" + inline(m[2]) + "</div>");
      else out.push('<div class="ln">' + inline(line) + "</div>");
      i++;
    }
    return out.join("");
  }
  var chips = document.getElementById("chips"), list = document.getElementById("list");
  var progress = document.getElementById("progress");
  function refresh() {
    var count = 0;
    msgs.forEach(function (_, idx) {
      var n = idx + 1, on = !!copied[n];
      if (on) count++;
      document.getElementById("chip" + n).classList.toggle("done", on);
      var card = document.getElementById("m" + n);
      card.classList.toggle("done", on);
      card.querySelector(".copy").textContent = on ? "Copied" : "Copy";
    });
    progress.textContent = count + " of " + msgs.length + " copied";
  }
  msgs.forEach(function (text, idx) {
    var n = idx + 1;
    var a = document.createElement("a");
    a.className = "chip"; a.href = "#m" + n; a.id = "chip" + n;
    a.innerHTML = '<span class="n">' + n + "</span>" + esc(shortTopics[idx]) + tag(n);
    chips.appendChild(a);
    var art = document.createElement("article");
    art.className = "msg"; art.id = "m" + n;
    var pct = Math.min(100, text.length / LIMIT * 100);
    var rows = Math.min(18, text.split("\n").length + 1);
    art.innerHTML =
      '<div class="msg-head">' +
        '<span class="n">' + n + "</span>" +
        "<h2>" + esc(topics[idx]) + "</h2>" + tag(n) +
        '<button type="button" class="copy" id="copy' + n + '" aria-label="Copy message ' + n + '">Copy</button>' +
      "</div>" +
      '<div class="meter"><div class="track"><div class="fill" style="width:' + pct.toFixed(1) + '%"></div></div>' +
        '<span class="count">' + text.length.toLocaleString("en-US") + " / 2,000</span></div>" +
      '<div class="preview">' + render(text) + "</div>" +
      '<p class="hint" hidden>Copy was blocked here. The text below is selected: press Ctrl+C (Cmd+C on a Mac).</p>' +
      '<details class="raw"><summary>Raw text</summary><textarea id="raw' + n + '" readonly rows="' + rows + '" spellcheck="false"></textarea></details>';
    art.querySelector("textarea").value = text;
    list.appendChild(art);
    art.querySelector(".copy").addEventListener("click", function () {
      var hint = art.querySelector(".hint");
      function mark() { copied[n] = true; save(); refresh(); hint.hidden = true; }
      function fallback() {
        var d = art.querySelector("details"); d.open = true;
        var ta = art.querySelector("textarea"); ta.focus(); ta.select();
        hint.hidden = false;
        copied[n] = true; save(); refresh();
      }
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(mark, fallback);
        else fallback();
      } catch (e) { fallback(); }
    });
  });
  document.getElementById("reset").addEventListener("click", function () { copied = {}; save(); refresh(); });
  refresh();
})();
</script>
"""

WORDS = {21: "Twenty-one", 22: "Twenty-two", 20: "Twenty", 23: "Twenty-three", 24: "Twenty-four"}
def nums(xs):
    xs = sorted(xs)
    return ", ".join(map(str, xs[:-1])) + (" and " if len(xs) > 1 else "") + str(xs[-1]) if xs else ""


changes = ""
if EDITED or ADDED:
    parts = []
    if EDITED:
        parts.append("edit message%s %s in place" % ("s" if len(EDITED) > 1 else "", nums(EDITED)))
    if ADDED:
        parts.append("post %s as new message%s, then repost Support last" % (nums(ADDED), "s" if len(ADDED) > 1 else ""))
    changes = ("<li><strong>Updating from the last version.</strong> For " + FAQ_VERSION + ": " + "; ".join(parts)
               + ". The rest are unchanged. Tagged Edit and New below.</li>")
page = (PAGE.replace("__CHANGES__", changes).replace("__DATA__", blob).replace("__N__", str(n)).replace("__HELP__", str(help_msg))
        .replace("__COUNT_WORD__", WORDS.get(n, str(n))).replace("__VERSION__", FAQ_VERSION))
open(sys.argv[1], "w", encoding="utf-8", newline="\n").write(page)
print("wrote", sys.argv[1], len(page), "bytes,", n, "messages; help in message", help_msg)
