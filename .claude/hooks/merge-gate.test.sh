#!/usr/bin/env bash
# Exercises .claude/hooks/merge-gate.sh: does it distinguish an invocation from
# a mention? Lives outside the repo so running it does not trip the hook it tests.
cd "$(dirname "$0")/../.." || exit 1

fail=0
run() {
  printf '%s' "$2" | jq -Rs '{tool_input:{command:.}}' | ./.claude/hooks/merge-gate.sh >/dev/null 2>&1
  got=$?
  if [ "$got" = "$3" ]; then verdict="ok"; else verdict="MISMATCH"; fail=1; fi
  printf '%-34s exit %s  expected %s  %s\n' "$1" "$got" "$3" "$verdict"
}

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

# ...but `gh api` on anything else is an ordinary read and must pass through.
run "gh api pr list"    'gh'' api repos/o/r/pulls --jq ".[].number"' 0
run "gh api one pr"     'gh'' api repos/o/r/pulls/220' 0
run "merge word in path" 'gh'' api repos/o/r/contents/docs/merge-notes.md' 0

exit $fail
