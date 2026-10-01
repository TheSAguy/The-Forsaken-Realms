#!/usr/bin/env python3
"""Turn the plane's GUIDE.md (the Game Guide & FAQ) into GUIDE.html - one self-contained page.

Round 386. The guide and the FAQ became one Markdown document; this renders it as a single HTML page that works
opened straight from disk (file://): inline CSS, no web fonts, no external scripts, the pictures by their relative
`guide/...` paths (the packager ships guide/ beside GAME_GUIDE.html, as it is beside GUIDE.md in the plane folder).

It understands the subset of Markdown the guide uses - headings (# to ####, GitHub-style anchors so the guide's own
`[..](#anchor)` links work), paragraphs, `-` and `1.` lists with indented continuation lines, pipe tables, `---`
rules, a picture alone on its line, **bold**, *italic*, `code`, [links](url) and bare https:// links. The Markdown
"Table of Contents" section is replaced by a generated one: a sticky sidebar on a wide screen, a block at the top on
a phone.

Re-run after every edit to GUIDE.md:

    python dev-tools/guide_html.py            # the repo's plane folder
    python dev-tools/guide_html.py --check    # also verify the output (balanced tags, every heading, every anchor)
    python dev-tools/guide_html.py IN.md OUT.html
"""
import html
import os
import re
import sys
from html.parser import HTMLParser

HERE = os.path.dirname(os.path.abspath(__file__))
PLANE = os.path.join(HERE, "..", "forge-gui", "res", "adventure", "The Forsaken Realms")
DEFAULT_IN = os.path.join(PLANE, "GUIDE.md")
DEFAULT_OUT = os.path.join(PLANE, "GUIDE.html")

TOC_HEADING = "table of contents"
TOC_DEPTH = 3  # h2 and h3 go in the generated contents

# ---------------------------------------------------------------------------------------------------------------
# inline markup


def slugify(text):
    """GitHub's heading anchor: lowercase, drop everything but letters/digits/space/-/_, spaces to hyphens."""
    s = strip_inline(text).lower()
    s = re.sub(r"[^\w\- ]", "", s, flags=re.UNICODE)
    return s.replace(" ", "-")


def strip_inline(text):
    """The heading's plain text (for anchors, the contents and the page title)."""
    t = re.sub(r"!\[([^\]]*)\]\([^)]*\)", r"\1", text)
    t = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", t)
    t = t.replace("**", "").replace("`", "")
    t = re.sub(r"(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])", r"\1", t)
    return t.strip()


def inline(text):
    """Markdown inline -> HTML. Code spans are cut out first so nothing inside them is touched."""
    codes = []

    def keep_code(m):
        codes.append("<code>" + html.escape(m.group(1), quote=False) + "</code>")
        return "\x00%d\x00" % (len(codes) - 1)

    text = re.sub(r"`([^`]+)`", keep_code, text)
    text = html.escape(text, quote=False)
    # pictures, then links (the URL inside is already escaped text - quote it for the attribute)
    text = re.sub(r"!\[([^\]]*)\]\(([^)\s]+)\)",
                  lambda m: '<img src="%s" alt="%s" loading="lazy">' % (m.group(2).replace('"', "&quot;"),
                                                                         m.group(1).replace('"', "&quot;")),
                  text)

    def link(m):
        label, url = m.group(1), m.group(2)
        ext = url.startswith("http://") or url.startswith("https://")
        attrs = ' target="_blank" rel="noopener"' if ext else ""
        return '<a href="%s"%s>%s</a>' % (url.replace('"', "&quot;"), attrs, label)

    text = re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", link, text)
    # bare URLs (not ones already inside an href or a link label)
    text = re.sub(r'(?<!["=>])(https?://[^\s<]+?)([.,;:)]?)(?=\s|$|<)',
                  lambda m: '<a href="%s" target="_blank" rel="noopener">%s</a>%s' % (m.group(1), m.group(1),
                                                                                       m.group(2)),
                  text)
    text = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])", r"<em>\1</em>", text)
    return re.sub(r"\x00(\d+)\x00", lambda m: codes[int(m.group(1))], text)


# ---------------------------------------------------------------------------------------------------------------
# blocks

LIST_RE = re.compile(r"^(\s*)([-*]|\d+\.)\s+(.*)$")
HEAD_RE = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
IMG_ONLY_RE = re.compile(r"^!\[[^\]]*\]\([^)]+\)$")


def split_row(line):
    s = line.strip()
    if s.startswith("|"):
        s = s[1:]
    if s.endswith("|"):
        s = s[:-1]
    return [c.strip() for c in s.split("|")]


def is_table_sep(line):
    return bool(re.match(r"^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$", line))


def parse(md):
    """Markdown text -> (title, headings, body html). headings = [(level, text, id)]."""
    lines = md.replace("\r\n", "\n").split("\n")
    out, headings, used = [], [], {}
    title = None
    i, n = 0, len(lines)
    para = []
    skipping_toc = False

    def flush_para():
        if para:
            text = " ".join(p.strip() for p in para)
            if IMG_ONLY_RE.match(text):
                out.append('<p class="figure">%s</p>' % inline(text))
            else:
                out.append("<p>%s</p>" % inline(text))
            para.clear()

    def unique(slug):
        if slug not in used:
            used[slug] = 0
            return slug
        used[slug] += 1
        return "%s-%d" % (slug, used[slug])

    while i < n:
        line = lines[i]
        stripped = line.strip()

        m = HEAD_RE.match(line)
        if m:
            flush_para()
            level, text = len(m.group(1)), m.group(2)
            if skipping_toc and level <= 2:
                skipping_toc = False
            if level == 2 and text.strip().lower() == TOC_HEADING:
                skipping_toc = True  # replaced by the generated contents
                i += 1
                continue
            if skipping_toc:
                i += 1
                continue
            if level == 1 and title is None:
                title = strip_inline(text)
                out.append('<h1 id="%s">%s</h1>' % (unique(slugify(text)), inline(text)))
            else:
                hid = unique(slugify(text))
                headings.append((level, strip_inline(text), hid))
                if level == 2:
                    out.append('<h2 id="%s">%s</h2>' % (hid, inline(text)))
                else:
                    out.append('<h%d id="%s">%s</h%d>' % (level, hid, inline(text), level))
            i += 1
            continue

        if skipping_toc:
            if re.match(r"^\s*-{3,}\s*$", line):
                skipping_toc = False  # the rule after the contents goes too
            i += 1
            continue

        if not stripped:
            flush_para()
            i += 1
            continue

        if re.match(r"^\s*(-{3,}|\*{3,}|_{3,})\s*$", line) and not para:
            out.append("<hr>")
            i += 1
            continue

        # table: a pipe row followed by a separator row
        if stripped.startswith("|") and i + 1 < n and is_table_sep(lines[i + 1]):
            flush_para()
            head = split_row(line)
            i += 2
            rows = []
            while i < n and lines[i].strip().startswith("|"):
                rows.append(split_row(lines[i]))
                i += 1
            t = ['<div class="table-wrap"><table>', "<thead><tr>"]
            t += ["<th>%s</th>" % inline(c) for c in head]
            t.append("</tr></thead><tbody>")
            for r in rows:
                r = (r + [""] * len(head))[:len(head)]
                t.append("<tr>" + "".join("<td>%s</td>" % inline(c) for c in r) + "</tr>")
            t.append("</tbody></table></div>")
            out.append("".join(t))
            continue

        lm = LIST_RE.match(line)
        if lm and len(lm.group(1)) == 0:
            flush_para()
            ordered = lm.group(2)[0].isdigit()
            items = []
            while i < n:
                lm = LIST_RE.match(lines[i])
                if lm and len(lm.group(1)) == 0 and lm.group(2)[0].isdigit() == ordered:
                    items.append([lm.group(3)])
                    if ordered and len(items) == 1:
                        start = int(lm.group(2)[:-1])
                    i += 1
                    continue
                # continuation: an indented non-blank line belongs to the current item
                if items and lines[i].strip() and lines[i][:1] in (" ", "\t"):
                    items[-1].append(lines[i].strip())
                    i += 1
                    continue
                break
            if ordered:
                attr = ' start="%d"' % start if start != 1 else ""
                out.append("<ol%s>" % attr)
            else:
                out.append("<ul>")
            for it in items:
                out.append("<li>%s</li>" % inline(" ".join(it)))
            out.append("</ol>" if ordered else "</ul>")
            continue

        para.append(line)
        i += 1

    flush_para()
    return title or "Guide", headings, "\n".join(out)


def toc_html(headings):
    """Nested contents: each h2, its h3s under it."""
    parts = ['<ol class="toc-list">']
    open_sub = False
    open_item = False
    for level, text, hid in headings:
        if level > TOC_DEPTH:
            continue
        if level == 2:
            if open_sub:
                parts.append("</ol>")
                open_sub = False
            if open_item:
                parts.append("</li>")
            parts.append('<li><a href="#%s">%s</a>' % (hid, html.escape(text, quote=False)))
            open_item = True
        else:
            if not open_item:  # an h3 before any h2 - give it a list of its own
                parts.append("<li>")
                open_item = True
            if not open_sub:
                parts.append("<ol>")
                open_sub = True
            parts.append('<li><a href="#%s">%s</a></li>' % (hid, html.escape(text, quote=False)))
    if open_sub:
        parts.append("</ol>")
    if open_item:
        parts.append("</li>")
    parts.append("</ol>")
    return "".join(parts)


CSS = """
:root {
  --bg: #f3ead3; --bg-edge: #e8dbb8; --panel: #fbf5e4; --fg: #2d2418; --muted: #6b5a41;
  --accent: #8a2f1c; --accent-2: #5d4a1f; --rule: #cdb98d; --code-bg: #eadfc2; --row: #f1e6c9;
  --head-bg: #e3d3a8; --link: #7a2a17; --link-hover: #a63c22; --shadow: rgba(60, 40, 10, 0.12);
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #1b1712; --bg-edge: #15120e; --panel: #241f18; --fg: #e9dfc8; --muted: #b3a385;
    --accent: #e2a65c; --accent-2: #d7c08e; --rule: #4a3f2e; --code-bg: #2f281e; --row: #211c16;
    --head-bg: #322a1f; --link: #efb36a; --link-hover: #ffd08f; --shadow: rgba(0, 0, 0, 0.4);
  }
}
* { box-sizing: border-box; }
html { scroll-behavior: smooth; -webkit-text-size-adjust: 100%; }
body {
  margin: 0; background: var(--bg); color: var(--fg);
  background-image: radial-gradient(ellipse at center, transparent 55%, var(--bg-edge) 100%);
  background-attachment: fixed;
  font: 17px/1.62 Georgia, "Palatino Linotype", "Book Antiqua", Palatino, serif;
}
.layout { max-width: 1240px; margin: 0 auto; padding: 0 16px 64px; }
main { min-width: 0; max-width: 800px; overflow-wrap: break-word; }
h1, h2, h3, h4 { font-family: "Palatino Linotype", "Book Antiqua", Palatino, Georgia, serif; line-height: 1.25;
  color: var(--accent); scroll-margin-top: 12px; }
h1 { font-size: 2.1rem; margin: 28px 0 4px; letter-spacing: 0.01em; }
h1 + p { color: var(--muted); margin-top: 0; }
h2 { font-size: 1.6rem; margin: 48px 0 12px; padding-bottom: 6px; border-bottom: 2px solid var(--rule); }
h3 { font-size: 1.22rem; margin: 30px 0 8px; color: var(--accent-2); }
h4 { font-size: 1.05rem; margin: 22px 0 6px; color: var(--accent-2); }
p { margin: 0 0 14px; }
ul, ol { padding-left: 1.4em; margin: 0 0 14px; }
li { margin: 4px 0; }
a { color: var(--link); text-decoration-thickness: 1px; text-underline-offset: 2px; }
a:hover { color: var(--link-hover); }
strong { color: inherit; }
code { font-family: Consolas, "Cascadia Mono", Menlo, monospace; font-size: 0.86em; background: var(--code-bg);
  padding: 1px 5px; border-radius: 4px; overflow-wrap: anywhere; }
hr { border: 0; border-top: 1px solid var(--rule); margin: 36px 0; }
.figure { text-align: center; margin: 18px 0 10px; }
.figure img { max-width: 100%; height: auto; border: 1px solid var(--rule); border-radius: 6px;
  box-shadow: 0 2px 10px var(--shadow); }
.table-wrap { overflow-x: auto; margin: 0 0 18px; border: 1px solid var(--rule); border-radius: 6px;
  background: var(--panel); }
table { border-collapse: collapse; width: 100%; font-size: 0.93rem; line-height: 1.45; }
th, td { padding: 7px 10px; text-align: left; vertical-align: top; border-bottom: 1px solid var(--rule); }
th { background: var(--head-bg); font-weight: bold; white-space: nowrap; }
tbody tr:nth-child(even) td { background: var(--row); }
tbody tr:last-child td { border-bottom: 0; }
.toc { background: var(--panel); border: 1px solid var(--rule); border-radius: 8px; margin: 16px 0 8px;
  box-shadow: 0 1px 6px var(--shadow); }
.toc summary { cursor: pointer; padding: 10px 14px; font-weight: bold; color: var(--accent); list-style: none;
  font-family: "Palatino Linotype", "Book Antiqua", Palatino, Georgia, serif; font-size: 1.05rem; }
.toc summary::-webkit-details-marker { display: none; }
.toc summary::before { content: "\\25B8"; display: inline-block; width: 1em; transition: transform .15s; }
.toc[open] summary::before { transform: rotate(90deg); }
.toc-list { margin: 0; padding: 0 14px 12px 34px; font-size: 0.92rem; line-height: 1.4; }
.toc-list > li { margin: 6px 0; }
.toc-list ol { list-style: none; padding-left: 12px; margin: 4px 0 6px; border-left: 2px solid var(--rule); }
.toc-list ol li { margin: 3px 0; }
.toc-list a { text-decoration: none; color: var(--fg); }
.toc-list a:hover { color: var(--link-hover); text-decoration: underline; }
.to-top { position: fixed; right: 16px; bottom: 16px; width: 40px; height: 40px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center; text-decoration: none; font-size: 1.2rem;
  background: var(--panel); color: var(--accent); border: 1px solid var(--rule); box-shadow: 0 2px 8px var(--shadow); }
@media (min-width: 1100px) {
  .layout { display: grid; grid-template-columns: 290px minmax(0, 1fr); gap: 44px; align-items: start; }
  .toc { position: sticky; top: 16px; max-height: calc(100vh - 32px); overflow-y: auto; margin-top: 28px; }
  .to-top { display: none; }
}
@media (max-width: 600px) {
  body { font-size: 16px; }
  h1 { font-size: 1.7rem; }
  h2 { font-size: 1.4rem; margin-top: 38px; }
  th { white-space: normal; }
}
@media print {
  .toc, .to-top { display: none; }
  body { background: #fff; color: #000; }
  .layout { display: block; }
}
"""

# Wide screen: the contents start open (a sidebar). Phone: closed, so the guide itself comes first. Without
# scripts it simply stays open.
SCRIPT = """
(function () {
  var d = document.getElementById('toc');
  if (!d || !window.matchMedia) return;
  var wide = window.matchMedia('(min-width: 1100px)');
  function sync() { d.open = wide.matches; }
  sync();
  if (wide.addEventListener) wide.addEventListener('change', sync); else if (wide.addListener) wide.addListener(sync);
  d.addEventListener('click', function (e) {
    if (e.target.tagName === 'A' && !wide.matches) d.open = false;
  });
})();
"""


def render(md):
    title, headings, body = parse(md)
    return """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>%s</title>
<style>%s</style>
</head>
<body id="top">
<div class="layout">
<details class="toc" id="toc" open>
<summary>Contents</summary>
<nav aria-label="Contents">%s</nav>
</details>
<main>
%s
</main>
</div>
<a class="to-top" href="#top" aria-label="Back to top">&#8593;</a>
<script>%s</script>
</body>
</html>
""" % (html.escape(title), CSS, toc_html(headings), body, SCRIPT)


# ---------------------------------------------------------------------------------------------------------------
# check

VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr"}


class Checker(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack, self.errors, self.ids, self.hrefs, self.imgs = [], [], set(), [], []
        self.headings, self._head = [], None

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if "id" in a:
            if a["id"] in self.ids:
                self.errors.append("duplicate id %r" % a["id"])
            self.ids.add(a["id"])
        if tag == "a" and "href" in a:
            self.hrefs.append(a["href"])
        if tag == "img":
            self.imgs.append(a.get("src", ""))
        if tag in ("h1", "h2", "h3", "h4", "h5", "h6"):
            self._head = [tag, a.get("id"), ""]
        if tag not in VOID:
            self.stack.append((tag, self.getpos()))

    def handle_endtag(self, tag):
        if tag in VOID:
            return
        if not self.stack or self.stack[-1][0] != tag:
            self.errors.append("unbalanced </%s> at line %d (open: %s)" % (
                tag, self.getpos()[0], self.stack[-1] if self.stack else None))
            # recover: pop to the matching tag if any
            for k in range(len(self.stack) - 1, -1, -1):
                if self.stack[k][0] == tag:
                    del self.stack[k:]
                    break
            return
        self.stack.pop()
        if self._head and tag == self._head[0]:
            self.headings.append((self._head[1], self._head[2].strip()))
            self._head = None

    def handle_data(self, data):
        if self._head:
            self._head[2] += data


def check(md, out_html, out_dir):
    c = Checker()
    c.feed(out_html)
    c.close()
    errors = list(c.errors)
    if c.stack:
        errors.append("unclosed tags: %s" % c.stack)
    # every Markdown heading (but the replaced contents) is a heading in the page, by its text
    html_heads = [t for _, t in c.headings]
    md_heads = []
    in_code = False
    for line in md.splitlines():
        m = HEAD_RE.match(line)
        if m and m.group(2).strip().lower() != TOC_HEADING:
            md_heads.append(strip_inline(m.group(2)))
    for h in md_heads:
        if h not in html_heads:
            errors.append("heading missing from the page: %r" % h)
    # every in-page link resolves; every picture exists
    for href in c.hrefs:
        if href.startswith("#") and href[1:] not in c.ids:
            errors.append("link to a missing anchor: %s" % href)
    for src in c.imgs:
        if not re.match(r"^[a-z]+:", src) and not os.path.exists(os.path.join(out_dir, src)):
            errors.append("picture not found beside the page: %s" % src)
    # stray Markdown that slipped through
    text_only = re.sub(r"<(script|style)\b.*?</\1>", "", out_html, flags=re.S)
    text_only = re.sub(r"<[^>]+>", "", text_only)
    for pat, what in ((r"\*\*", "**"), (r"\]\(", "]("), (r"(?m)^#{1,6} ", "# heading")):
        hits = re.findall(pat, text_only)
        if hits:
            errors.append("leftover Markdown %s x%d" % (what, len(hits)))
    return errors, len(md_heads), len(c.hrefs), len(c.imgs)


def main(argv):
    args = [a for a in argv if not a.startswith("--")]
    do_check = "--check" in argv
    src = args[0] if len(args) > 0 else DEFAULT_IN
    dst = args[1] if len(args) > 1 else DEFAULT_OUT
    with open(src, encoding="utf-8") as f:
        md = f.read()
    page = render(md)
    with open(dst, "w", encoding="utf-8", newline="\n") as f:
        f.write(page)
    print("wrote %s (%d bytes) from %s" % (os.path.normpath(dst), len(page.encode("utf-8")), os.path.normpath(src)))
    if do_check:
        errors, nh, nl, ni = check(md, page, os.path.dirname(os.path.abspath(dst)))
        print("check: %d heading(s), %d link(s), %d picture(s)" % (nh, nl, ni))
        for e in errors:
            print("  PROBLEM " + e)
        if errors:
            return 1
        print("check: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
