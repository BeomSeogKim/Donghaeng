#!/usr/bin/env bash
#
# Rebuild the subset webfonts used by the design-system previews.
#
# Only needed when the preview text changes — build.py warns when a part uses a
# character the current subset does not cover, and that warning is the signal to
# run this. The subsets are committed, so an ordinary preview build needs no
# font tooling at all.
#
#   ./design/fonts/subset.sh
#
# Requires fonttools + brotli. If they are not installed, this creates a
# throwaway venv rather than touching the system Python:
#
#   python3 -m venv /tmp/fenv && /tmp/fenv/bin/pip install fonttools brotli
#   PY=/tmp/fenv/bin/python ./design/fonts/subset.sh
#
set -euo pipefail
cd "$(dirname "$0")"

PY="${PY:-python3}"
"$PY" -c 'import fontTools, brotli' 2>/dev/null || {
  echo "fonttools/brotli missing. See the header of this script." >&2; exit 1; }

# charset.txt is recomputed here from the parts, so this script is the single
# place that decides what the subsets carry. build.py only *checks* against it.
"$PY" - <<'PYEOF'
import io, re, glob
chars = set()
for f in glob.glob("../components/parts/*.html"):
    s = io.open(f, encoding="utf-8").read()
    s = re.sub(r"<style>.*?</style>", "", s, flags=re.S)
    s = re.sub(r"<!--(.*?)-->", r"\1", s, flags=re.S)   # @dsCard names are rendered
    s = re.sub(r"<[^>]+>", " ", s)
    chars |= set(s)
chars |= set("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")
chars |= set(" .,:;·—–-()[]{}/\\%&+=*#'\"!?~<>|@^_□…±×÷°")
chars = {c for c in chars if c.isprintable() and not c.isspace()} | {" "}
io.open("charset.txt", "w", encoding="utf-8").write("".join(sorted(chars)))
print("charset: %d characters" % len(chars))
PYEOF

UNICODES=$("$PY" - <<'PYEOF'
import io
cs = io.open("charset.txt", encoding="utf-8").read()
print(",".join("U+%04X" % ord(c) for c in sorted(set(cs))))
PYEOF
)

sub () { # <source> <output>
  "$PY" -m fontTools.subset "$1" \
    --unicodes="$UNICODES" \
    --flavor=woff2 \
    --output-file="$2" \
    --layout-features=tnum,pnum,kern,liga,calt \
    --no-hinting --desubroutinize
  printf '  %-28s %8s\n' "$(basename "$2")" "$(du -h "$2" | cut -f1)"
}

echo "subsetting to $(wc -m < charset.txt | tr -d ' ') characters:"
sub .src-Pretendard.woff2 Pretendard.subset.woff2
sub .src-RIDIBatang.otf   RIDIBatang.subset.woff2
echo "done — rebuild previews with: python3 design/components/build.py"
