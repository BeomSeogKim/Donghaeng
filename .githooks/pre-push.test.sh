#!/usr/bin/env bash
# Exercises .githooks/pre-push. It is the only thing standing between an agent
# session and a direct push to `main` — branch protection is unavailable on this
# repo — and until 2026-08-24 it was also the only guard here with no test and
# no mention in CI. The three .claude/hooks/ suites all had both.
#
# The hook reads its refs from stdin in git's format:
#     <local ref> <local sha> <remote ref> <remote sha>
cd "$(dirname "$0")/.." || exit 1

hook=.githooks/pre-push
fail=0

run() {
  printf '%s\n' "$2" | "$hook" origin git@github.com:o/r.git >/dev/null 2>&1
  got=$?
  if [ "$got" = "$3" ]; then verdict="ok"; else verdict="MISMATCH"; fail=1; fi
  printf '%-40s exit %s  expected %s  %s\n' "$1" "$got" "$3" "$verdict"
}

sha=0000000000000000000000000000000000000000
head=1111111111111111111111111111111111111111

# --- main is push-protected
run "push to main"            "refs/heads/main $head refs/heads/main $sha" 1
run "main among several refs" "refs/heads/x $head refs/heads/x $sha
refs/heads/main $head refs/heads/main $sha" 1
run "local branch renamed to main" "refs/heads/anything $head refs/heads/main $sha" 1

# --- everything else is a branch, which is how work is supposed to land
run "push a feature branch"   "refs/heads/api/x $head refs/heads/api/x $sha" 0
run "branch named main-ish"   "refs/heads/mainline $head refs/heads/mainline $sha" 0
run "delete a branch"         "(delete) $sha refs/heads/api/x $head" 0
run "nothing to push"         "" 0

# --- the budget gate runs BEFORE the ref check, so an over-budget rule file
# stops the push whatever branch it is aimed at. Exercised against a fake tree
# because agents-budget.sh always measures the real repo it lives in.
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/.claude/hooks" "$tmp/.githooks" "$tmp/api" "$tmp/web" "$tmp/design"
cp .claude/hooks/agents-budget.sh "$tmp/.claude/hooks/"
cp "$hook" "$tmp/.githooks/"
for f in AGENTS.md api/AGENTS.md web/AGENTS.md design/AGENTS.md; do
  printf 'x\n%.0s' $(seq 1 400) > "$tmp/$f"
done

got=$(cd "$tmp" && printf 'refs/heads/x %s refs/heads/x %s\n' "$head" "$sha" \
  | .githooks/pre-push origin git@github.com:o/r.git >/dev/null 2>&1; echo $?)
if [ "$got" = "1" ]; then verdict="ok"; else verdict="MISMATCH"; fail=1; fi
printf '%-40s exit %s  expected %s  %s\n' "over-budget AGENTS.md blocks" "$got" 1 "$verdict"

exit $fail
