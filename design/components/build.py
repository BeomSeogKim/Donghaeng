#!/usr/bin/env python3
"""
Build standalone component previews for the Donghaeng design system.

Each part in parts/ is a fragment: a first-line @dsCard metadata comment, then
optional <style>, then markup. This script wraps each one in a self-contained
page with design/tokens.css inlined, so a preview can be rendered anywhere
(claude.ai/design pane, a browser, an artifact) with no relative fetches.

Tokens are inlined rather than copied so there stays exactly one source of
truth: edit design/tokens.css, rebuild, and every preview follows.

    python3 design/components/build.py

Writes dist/*.html and dist/index.json (card metadata for DesignSync).

Note on the typeface: previews name Pretendard first but fall back to the
system Korean face. Embedding the 2.7MB webfont in all 13 cards would cost
~30MB for a rendering nicety; the published spec page carries the real face.
"""

import html
import io
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TOKENS = os.path.join(HERE, os.pardir, "tokens.css")
PARTS = os.path.join(HERE, "parts")
DIST = os.path.join(HERE, "dist")

CARD_RE = re.compile(r"<!--\s*@dsCard\s+(.*?)-->", re.S)
ATTR_RE = re.compile(r'(\w+)="([^"]*)"')

SHELL = """<!doctype html>
<html lang="ko" data-theme="{theme}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title} — 동행</title>
<style>
{tokens}

/* ── preview chrome — deliberately quiet; the component is the subject ── */
*, *::before, *::after {{ box-sizing: border-box; }}
html, body {{ margin: 0; }}
body {{
  font-family: var(--dh-font-sans);
  font-size: var(--dh-text-body);
  line-height: var(--dh-leading-body);
  color: var(--dh-ink);
  background: var(--dh-ground);
  -webkit-font-smoothing: antialiased;
  padding: var(--dh-space-6);
}}
.pv-head {{
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--dh-space-4);
  flex-wrap: wrap;
  margin-bottom: var(--dh-space-5);
  padding-bottom: var(--dh-space-3);
  border-bottom: 1px solid var(--dh-line);
}}
.pv-head h1 {{
  margin: 0;
  font-size: var(--dh-text-title);
  font-weight: var(--dh-weight-bold);
  letter-spacing: -0.015em;
  line-height: var(--dh-leading-snug);
}}
.pv-head p {{
  margin: 0;
  font-size: var(--dh-text-meta);
  color: var(--dh-ink-muted);
}}
.pv-grid {{ display: flex; flex-direction: column; gap: var(--dh-space-6); }}
.pv-set {{ display: flex; flex-direction: column; gap: var(--dh-space-3); }}
.pv-label {{
  font-size: var(--dh-text-label);
  font-weight: var(--dh-weight-semibold);
  letter-spacing: var(--dh-tracking-label);
  text-transform: uppercase;
  color: var(--dh-ink-faint);
}}
.pv-row {{ display: flex; flex-wrap: wrap; align-items: center; gap: var(--dh-space-3); }}
.pv-stage {{ background: var(--dh-surface); border: 1px solid var(--dh-line); }}
.pv-note {{
  font-size: var(--dh-text-meta);
  line-height: 1.55;
  color: var(--dh-ink-muted);
  border-left: 2px solid var(--dh-line-strong);
  padding-left: var(--dh-space-3);
  max-width: 34rem;
}}
.pv-note b {{ color: var(--dh-ink); font-weight: var(--dh-weight-semibold); }}
.pv-note.warn {{ border-left-color: var(--dh-hong); }}
:where(a, button, input, select, textarea, [tabindex]):focus-visible {{
  outline: 2px solid var(--dh-focus);
  outline-offset: 2px;
}}
{style}
</style>
</head>
<body>
<div class="pv-head">
  <h1>{title}</h1>
  <p>{subtitle}</p>
</div>
{body}
</body>
</html>
"""


def parse_part(text):
    m = CARD_RE.search(text)
    if not m:
        raise SystemExit("part is missing its @dsCard first-line comment")
    meta = dict(ATTR_RE.findall(m.group(1)))
    body = text[m.end():].strip()

    styles = re.findall(r"<style>(.*?)</style>", body, re.S)
    body = re.sub(r"<style>.*?</style>", "", body, flags=re.S).strip()
    return meta, "\n".join(s.strip() for s in styles), body


def main():
    tokens = io.open(TOKENS, encoding="utf-8").read()
    os.makedirs(DIST, exist_ok=True)

    names = sorted(n for n in os.listdir(PARTS) if n.endswith(".html"))
    if not names:
        raise SystemExit("no parts found")

    cards = []
    for name in names:
        raw = io.open(os.path.join(PARTS, name), encoding="utf-8").read()
        meta, style, body = parse_part(raw)

        out_name = re.sub(r"^\d+-", "", name)
        page = SHELL.format(
            theme=meta.get("theme", "light"),
            title=html.escape(meta.get("name", out_name)),
            subtitle=html.escape(meta.get("subtitle", "")),
            tokens=tokens,
            style=style,
            body=body,
        )
        io.open(os.path.join(DIST, out_name), "w", encoding="utf-8").write(page)

        cards.append({
            "name": meta.get("name", out_name),
            "path": "components/" + out_name,
            "subtitle": meta.get("subtitle", ""),
            "group": meta.get("group", "Components"),
            "viewport": {
                "width": int(meta.get("width", 720)),
                "height": int(meta.get("height", 520)),
            },
        })

    io.open(os.path.join(DIST, "index.json"), "w", encoding="utf-8").write(
        json.dumps(cards, ensure_ascii=False, indent=2)
    )
    print("built {} previews -> {}".format(len(cards), os.path.relpath(DIST)))
    for c in cards:
        print("  {:<28} {}".format(c["path"], c["group"]))


if __name__ == "__main__":
    sys.exit(main())
