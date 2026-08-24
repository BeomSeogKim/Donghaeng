#!/usr/bin/env bash
# Exercises .githooks/pre-push. It is the only thing standing between a session
# and a direct push to `main` — branch protection is unavailable on this repo —
# and until 2026-08-24 it was also the only guard here with no test and no
# mention in CI. The three .claude/hooks/ suites all had both.
#
# The hook reads its refs from stdin in git's format:
#     <local ref> <local sha> <remote ref> <remote sha>
#
# Every case runs in a FAKE tree, not this repo. The hook checks the AGENTS.md
# budgets before it looks at a single ref, and the real root file sits at exactly
# 220/220 — so run against the real tree, adding one line to AGENTS.md would flip
# every ref case to a failure naming the wrong thing (found in review).
cd "$(dirname "$0")/.." || exit 1

repo=$(pwd)
fail=0
sha=0000000000000000000000000000000000000000
head=1111111111111111111111111111111111111111

# A tree the hook can run in: its own copy, its budget script, and rule files
# sized by the caller.
make_tree() {
  t=$(mktemp -d)
  mkdir -p "$t/.claude/hooks" "$t/.githooks" "$t/api" "$t/web" "$t/design"
  cp "$repo/.claude/hooks/agents-budget.sh" "$t/.claude/hooks/"
  cp "$repo/.githooks/pre-push" "$t/.githooks/"
  for f in AGENTS.md api/AGENTS.md web/AGENTS.md design/AGENTS.md; do
    printf 'x\n%.0s' $(seq 1 "$1") > "$t/$f"
  done
  printf '%s' "$t"
}

within=$(make_tree 10)
over=$(make_tree 400)
trap 'rm -rf "$within" "$over"' EXIT

run() {
  got=$(cd "$3" && printf '%s\n' "$2" | .githooks/pre-push origin git@github.com:o/r.git >/dev/null 2>&1; echo $?)
  if [ "$got" = "$4" ]; then verdict="ok"; else verdict="MISMATCH"; fail=1; fi
  printf '%-40s exit %s  expected %s  %s\n' "$1" "$got" "$4" "$verdict"
}

# --- main is push-protected
run "push to main"            "refs/heads/main $head refs/heads/main $sha" "$within" 1
run "main among several refs" "refs/heads/x $head refs/heads/x $sha
refs/heads/main $head refs/heads/main $sha" "$within" 1
run "local branch renamed to main" "refs/heads/anything $head refs/heads/main $sha" "$within" 1

# --- everything else is a branch, which is how work is supposed to land
run "push a feature branch"   "refs/heads/api/x $head refs/heads/api/x $sha" "$within" 0
run "branch named main-ish"   "refs/heads/mainline $head refs/heads/mainline $sha" "$within" 0
run "delete a branch"         "(delete) $sha refs/heads/api/x $head" "$within" 0
run "nothing to push"         "" "$within" 0

# --- the budget gate runs BEFORE the ref check, so an over-budget rule file
#     stops the push whatever branch it is aimed at
run "over-budget blocks a branch" "refs/heads/x $head refs/heads/x $sha" "$over" 1
run "over-budget blocks main too" "refs/heads/main $head refs/heads/main $sha" "$over" 1

exit $fail
