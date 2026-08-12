#!/usr/bin/env bash
# Exercises .claude/hooks/closes-guard.sh: does it tell a real closing trailer
# from prose that describes one? The second half matters as much as the first —
# this repo's notes and hooks quote the broken form on purpose.
cd "$(dirname "$0")/../.." || exit 1

fail=0
run() {
  printf '%s' "$2" | jq -Rs '{tool_input:{command:.}}' | ./.claude/hooks/closes-guard.sh >/dev/null 2>&1
  got=$?
  if [ "$got" = "$3" ]; then verdict="ok"; else verdict="MISMATCH"; fail=1; fi
  printf '%-40s exit %s  expected %s  %s\n' "$1" "$got" "$3" "$verdict"
}

# --- multi-issue trailers -> must block (2)
run "comma list, -m" 'git commit -m "fix

Closes #33, #35"' 2

run "comma list, heredoc" "git commit -F - <<'MSG'
title

Closes #33, #35
MSG" 2

run "space separated" 'git commit -m "t

closes #12 #13"' 2

run "lowercase fixes" 'git commit -m "t

fixes #7, #8"' 2

run "resolve variant" 'git commit -m "t

Resolved #1, #2"' 2

run "gh pr create body" 'gh pr create --title x --body "Closes #40, #41"' 2

run "chained after &&" 'git add -A && git commit -m "t

Closes #5, #6"' 2

# --- correct usage -> must pass (0)
run "repeated keyword" 'git commit -m "t

Closes #33, closes #35"' 0

run "one per line" "git commit -F - <<'MSG'
t

Closes #33
Closes #35
MSG" 0

run "single issue" 'git commit -m "t

Closes #83"' 0

run "single + bare refs elsewhere" 'git commit -m "t

Refs #1 #2

Closes #83"' 0

# --- prose that describes the rule -> must pass (0)
run "backticked example in body" "git commit -F - <<'MSG'
훅을 만든다

\`Closes #33, #35\` 는 #33만 닫는다.
MSG" 0

run "grep for the pattern" 'grep -rn "Closes #33, #35" notes/' 0

run "inline prose, not line-start" 'git commit -m "t

the form Closes #33, #35 is wrong"' 0

run "unrelated command" 'git status' 0

exit $fail
