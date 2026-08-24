#!/usr/bin/env bash
# Exercises .claude/hooks/merge-gate.sh. Two questions: does it tell an
# invocation from a mention, and once it has one, does it ask the right things
# about the right PR?
#
# Every case runs against a stub `gh` on PATH. Before 2026-08-24 they ran against
# the real one, unauthenticated in CI — so every \"expected 2\" passed because
# `gh pr checks` errored, and the whole staleness and reserved-surface path could
# be deleted with the suite still green. A control nothing exercises is a control
# nobody knows the shape of (ci.yml).
cd "$(dirname "$0")/../.." || exit 1

fail=0

stub=$(mktemp -d)
trap 'rm -rf "$stub"' EXIT
cat > "$stub/gh" <<'STUB'
#!/usr/bin/env bash
[ -n "${STUB_LOG:-}" ] && printf '%s\n' "$*" >> "$STUB_LOG"
case "$1 $2" in
  "pr checks") exit "${STUB_CHECKS:-1}" ;;
  "pr view")
    # One shape, answered whole. The previous stub recognised --json values by
    # scanning "$@" and fell through to a base/head object for anything it did
    # not recognise, so an unknown field came back non-empty and every
    # fail-closed guard in the hook passed for the wrong reason (found in
    # review on #230). Unrecognised now exits 1.
    case " $* " in *" --json "*) ;; *) exit 1 ;; esac
    jq -n \
      --arg base "${STUB_BASE-main}" \
      --arg head "${STUB_HEAD-feat}" \
      --arg oid "${STUB_HEAD_OID-a1b2c3d4e5f60718293a4b5c6d7e8f9012345678}" \
      --arg files "${STUB_FILES-web/src/x.tsx}" \
      --arg comment "${STUB_REVIEW-Reviewed-at: a1b2c3d4e5f60718293a4b5c6d7e8f9012345678
three findings, all fixed}" \
      --arg review "${STUB_PRREVIEW-}" '
      {
        baseRefName: $base,
        headRefName: $head,
        headRefOid:  $oid,
        files:    ($files   | split(" ") | map(select(length > 0)) | map({path: .})),
        comments: (if ($comment | length) > 0 then [{body: $comment}] else [] end),
        reviews:  (if ($review  | length) > 0 then [{body: $review}]  else [] end)
      }'
    exit 0 ;;
esac
[ "$1" = api ] && { printf '%s\n' "${STUB_BEHIND-0}"; exit 0; }
exit 1
STUB
chmod +x "$stub/gh"

export GH="$stub/gh"

run() {
  printf '%s' "$2" | jq -Rs '{tool_input:{command:.}}' | ./.claude/hooks/merge-gate.sh >/dev/null 2>&1
  got=$?
  if [ "$got" = "$3" ]; then verdict="ok"; else verdict="MISMATCH"; fail=1; fi
  printf '%-40s exit %s  expected %s  %s\n' "$1" "$got" "$3" "$verdict"
}

# ── Is this a merge at all? The stub reports a red check, so a detected merge
#    is a 2 and a mention falls out at 0 before any call is made.
# invocations -> must block (2)
run "bare"              'gh'' pr merge 99 --squash' 2
run "chained"           'git log -1 && gh'' pr merge --squash' 2
run "own line"          'set -e
gh'' pr merge 99' 2
run "after then"        'if true; then gh'' pr merge; fi' 2

# mentions -> must pass through (0)
run "unrelated command" 'git status' 0
run "inside a quote"    'echo "run gh'' pr merge later"' 0
run "grep for the rule" 'grep -rn "gh'' pr merge" notes/' 0
run "permission rule string" 'jq -e ".permissions.allow|index(\"Bash(gh"" pr merge*)\")" .claude/settings.json' 0
run "subshell is still a merge" '(gh'' pr merge 9)' 2
run "commit prose"      "git commit -F - <<'MSG'
훅이 gh"" pr merge를 막는다
gh"" pr merge 5
MSG" 0

# The REST call underneath `gh pr merge`. It walked through this hook until
# 2026-08-24 — a spelling was being matched, not an act.
run "gh api PUT merge"  'gh'' api --method PUT repos/o/r/pulls/220/merge' 2
run "gh api -X merge"   'gh'' api -X PUT /repos/o/r/pulls/7/merge -f merge_method=squash' 2
run "gh api chained"    'git log -1 && gh'' api --method PUT repos/o/r/pulls/9/merge' 2
run "gh api PUT, PR unreadable" 'gh'' api -X PUT "repos/$R/pulls/$N/merge"' 2

# ...but `gh api` on anything else is an ordinary read and must pass through.
run "gh api pr list"    'gh'' api repos/o/r/pulls --jq ".[].number"' 0
run "gh api one pr"     'gh'' api repos/o/r/pulls/220' 0
run "merge word in path" 'gh'' api repos/o/r/contents/docs/merge-notes.md' 0
run "GET on the merge path" 'gh'' api repos/o/r/pulls/220/merge' 0

# Two merges in one command: only the first could be checked.
run "two merges"        'gh'' pr merge 220 && gh'' pr merge 221' 2

# ── Which PR does it ask about? A flag-first invocation used to yield no number
#    and silently fall back to the CURRENT branch's PR — routinely a different,
#    greener one in a worktree (found in review, 2026-08-24).
asks() {
  log=$(mktemp)
  STUB_LOG="$log" STUB_CHECKS=0 STUB_BEHIND=0 \
    bash -c "printf '%s' \"\$1\" | jq -Rs '{tool_input:{command:.}}' | ./.claude/hooks/merge-gate.sh" _ "$2" >/dev/null 2>&1
  got=$(grep -m1 '^pr checks' "$log" | awk '{print $3}')
  # Every call that decides the merge must name the same PR. `pr view` was added
  # after this helper and went unasserted — the shape of the finding that
  # started all of this (review on #228, raised again on #230).
  viewed=$(grep -m1 '^pr view' "$log" | awk '{print $3}')
  case "$viewed" in --*) viewed="" ;; esac
  rm -f "$log"
  if [ "$got" = "$3" ] && [ "$viewed" = "$3" ]; then verdict="ok"; else verdict="MISMATCH"; fail=1; fi
  printf '%-40s checks[%s] view[%s]  expected [%s]  %s\n' "$1" "$got" "$viewed" "$3" "$verdict"
}
asks "number first"     'gh'' pr merge 220 --squash' 220
asks "flags first"      'gh'' pr merge --squash 220' 220
asks "flags either side" 'gh'' pr merge --squash --delete-branch 228' 228
asks "bare means current" 'gh'' pr merge --squash' ''

# ── Green. Now the two questions green cannot answer.
green() { STUB_CHECKS=0 "$@"; }

run_env() {
  name=$1; shift; expected=$1; shift
  env "$@" ./.claude/hooks/merge-gate.sh < <(printf '%s' "gh pr merge 220" | jq -Rs '{tool_input:{command:.}}') >/dev/null 2>&1
  got=$?
  if [ "$got" = "$expected" ]; then verdict="ok"; else verdict="MISMATCH"; fail=1; fi
  printf '%-40s exit %s  expected %s  %s\n' "$name" "$got" "$expected" "$verdict"
}

run_env "green, current, ordinary files" 0 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0
run_env "green but 3 behind base"        2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=3
run_env "green, behind_by unreadable"    2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=
run_env "green, behind_by not a number"  2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=null
run_env "green, base branch unreadable"  2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 STUB_BASE=
run_env "green, file list unreadable"    2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 STUB_FILES=
run_env "green, touches a migration"     2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_FILES="web/src/x.tsx api/src/main/resources/db/migration/V7__x.sql"
run_env "green, touches auth"            2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_FILES="api/src/main/kotlin/com/donghaeng/auth/SecurityConfig.kt"
run_env "green, ordinary api change"     0 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_FILES="api/src/main/kotlin/com/donghaeng/guest/GuestService.kt"


# -- The verdict: did a reviewer look at THIS content, and say something?
oid=a1b2c3d4e5f60718293a4b5c6d7e8f9012345678
old=0000000000000000000000000000000000000000

run_env "no verdict recorded"            2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 STUB_REVIEW=
run_env "verdict on different content"   2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW="Reviewed-at: $old
found nothing"
run_env "short sha is enough"            0 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW="Reviewed-at: a1b2c3d
found nothing"
run_env "six hex is below the floor"     2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW="Reviewed-at: a1b2c
found nothing"
run_env "case is normalised"             0 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW="REVIEWED-AT: A1B2C3D4E5F60718293A4B5C6D7E8F9012345678
found nothing"

# A quotation is not a verdict. This file strips heredoc bodies for the same
# reason; the first cut of the marker check did not get the lesson, and the
# decision record itself contains a fenced example (found in review on #230).
run_env "marker inside a fence"          2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW="the rule is:
\`\`\`
Reviewed-at: $oid
\`\`\`
that is what it looks like"
run_env "marker quoted in a reply"       2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW="> Reviewed-at: $oid
I did not write that"
run_env "marker mid-line is prose"       2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW="a line saying Reviewed-at: $oid must start the line
so this one does not count"

# A verdict whose findings are all in a code block still said something. The
# fence rule is there to disqualify a marker, not the prose around it — and
# quoting the offending line is what a reviewer normally does. The guard on
# that arm needs its false side asserted too, or it can be deleted with the
# suite staying green while an empty fence starts clearing.
run_env "findings inside a fence still count" 0 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW="Reviewed-at: $oid
\`\`\`
api/Foo.kt:12 — off by one
\`\`\`"

run_env "marker plus an empty fence"      2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW="Reviewed-at: $oid
\`\`\`
\`\`\`"
run_env "marker plus a blank fence"       2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW="Reviewed-at: $oid
\`\`\`

\`\`\`"

# A sha and nothing else says nothing about a review.
run_env "marker with no verdict text"    2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW="Reviewed-at: $oid"

# The production shape once a re-review happens: the stale comment stays.
run_env "stale verdict beside a current one" 0 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW="Reviewed-at: $old
first pass, four findings
Reviewed-at: $oid
all four fixed"

# A verdict left as a review rather than a comment is still a verdict.
run_env "verdict in reviews, not comments" 0 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW= STUB_PRREVIEW="Reviewed-at: $oid
found nothing"

# The verdict in the SECOND body, with a first body that carries none. The
# NUL-separated parser kept only the first record and silently lost this.
run_env "verdict in the second body"     0 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW="just a note, no verdict here" \
  STUB_PRREVIEW="Reviewed-at: $oid
found nothing"

# A rebase stales the verdict, deliberately: a change replayed onto a moved
# `main` is not the change that was read, and #138 was two green PRs whose
# combination was broken. Keying to the tree does not help either — a rebase
# changes the tree (verified) — and `git patch-id`, which would carry, is not
# used on purpose.

# -- Shapes the first cut of the parser got wrong (review on #230).
run_env "CRLF body, as the web UI sends" 0 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW="$(printf 'Reviewed-at: %s\r\nall clean\r\n' "$oid")"
run_env "tilde fence is a fence too"     2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW="the rule is:
~~~
Reviewed-at: $oid
~~~
that is all"
run_env "current marker is not last"     0 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW="Reviewed-at: $oid
all fixed. the previous pass said:
Reviewed-at: $old
four findings"
run_env "body containing the old sentinel" 0 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW="Reviewed-at: $oid
xx-end-of-body-xx
that line used to cut the body in half"

run_env "head oid unreadable"            2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 STUB_HEAD_OID=
run_env "reserved beats the verdict"     2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_FILES="api/src/main/resources/db/migration/V7__x.sql"

exit $fail
