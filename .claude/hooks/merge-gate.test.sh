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
  "repo view") printf '%s\n' "${STUB_REPO-o/r}"; exit 0 ;;
  "pr view")
    for a in "$@"; do
      case "$a" in
        *files*)       printf '%s\n' ${STUB_FILES-web/src/x.tsx}; exit 0 ;;
        *headRefOid*)  printf '%s\n' "${STUB_HEAD_OID-a1b2c3d4e5f60718293a4b5c6d7e8f9012345678}"; exit 0 ;;
        *comments*)    printf '%s\n' "${STUB_REVIEW-Reviewed-at: a1b2c3d4e5f60718293a4b5c6d7e8f9012345678}"; exit 0 ;;
      esac
    done
    printf '{"baseRefName":"%s","headRefName":"%s"}\n' "${STUB_BASE-main}" "${STUB_HEAD-feat}"
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
  STUB_LOG="$log" bash -c "printf '%s' \"\$1\" | jq -Rs '{tool_input:{command:.}}' | ./.claude/hooks/merge-gate.sh" _ "$2" >/dev/null 2>&1
  got=$(grep -m1 '^pr checks' "$log" | awk '{print $3}')
  rm -f "$log"
  if [ "$got" = "$3" ]; then verdict="ok"; else verdict="MISMATCH"; fail=1; fi
  printf '%-40s asked [%s]  expected [%s]  %s\n' "$1" "$got" "$3" "$verdict"
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
run_env "green, repo slug unreadable"    2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 STUB_REPO=
run_env "green, base branch unreadable"  2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 STUB_BASE=
run_env "green, file list unreadable"    2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 STUB_FILES=
run_env "green, touches a migration"     2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_FILES="web/src/x.tsx api/src/main/resources/db/migration/V7__x.sql"
run_env "green, touches auth"            2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_FILES="api/src/main/kotlin/com/donghaeng/auth/SecurityConfig.kt"
run_env "green, ordinary api change"     0 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_FILES="api/src/main/kotlin/com/donghaeng/guest/GuestService.kt"

# ── And the one condition that is not mechanical in origin: did a reviewer look
#    at THIS commit? Tied to the head oid so it goes stale by itself.
oid=a1b2c3d4e5f60718293a4b5c6d7e8f9012345678
run_env "reviewed at the current head"   0 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0
run_env "no review recorded"             2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 STUB_REVIEW=
run_env "review predates the last push"  2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW="Reviewed-at: 0000000000000000000000000000000000000000"
run_env "short sha is enough"            0 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW="Reviewed-at: a1b2c3d"
run_env "seven hex is the floor"         2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW="Reviewed-at: a1b2c"
run_env "lowercase key accepted"         0 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW="reviewed-at: $oid"
run_env "prose mentioning it is not it"  2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW="the rule is that a Reviewed-at: $oid line must start its own line"
run_env "verdict among other comments"   0 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_REVIEW="looks good to me
Reviewed-at: $oid
three findings, all fixed"
run_env "head oid unreadable"            2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 STUB_HEAD_OID=
run_env "reserved beats reviewed"        2 GH="$stub/gh" STUB_CHECKS=0 STUB_BEHIND=0 \
  STUB_FILES="api/src/main/resources/db/migration/V7__x.sql"

exit $fail
