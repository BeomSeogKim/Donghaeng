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

# --- a trailer added after the fact closes just as silently (2026-08-24)
run "gh pr edit body" 'gh'' pr edit 12 --body "Closes #40, #41"' 2
run "gh pr edit, correct form" 'gh'' pr edit 12 --body "Closes #40, closes #41"' 0

# An issue body and a comment close nothing — GitHub reads closing keywords from
# a PR description and from commit messages only. Blocking them would refuse
# prose in the very issues that discuss this rule.
run "gh issue comment is prose" 'gh'' issue comment 12 --body "Closes #40, #41"' 0
run "gh pr comment is prose" 'gh'' pr comment 12 --body "Closes #40, #41"' 0

# --- a heredoc that writes a FILE is a document, not a message (2026-08-24)
run "file write quoting the rule" $'cat > audit.html <<\'HTML\'\n<div>Closes #33, #35 는 #33만 닫는다</div>\nHTML' 0
run "file write after cd &&" $'cd /tmp && cat > note.md <<\'MD\'\nCloses #33, #35\nMD' 0
run "tee is a file sink too" $'tee notes/x.md > /dev/null <<\'MD\'\nCloses #33, #35\nMD' 0

# ...but a file that BECOMES a message is a message. This is the #83 failure,
# and the first cut of the strip above reintroduced it (found in review).
run "heredoc file fed to git commit -F" $'cat > /tmp/m.txt <<\'EOF\'\nt\n\nCloses #33, #35\nEOF\ngit commit -F /tmp/m.txt' 2
run "heredoc file fed to --body-file" $'cat > /tmp/body.md <<\'EOF\'\nCloses #33, #35\nEOF\ngh'' pr create --body-file /tmp/body.md' 2
run "consumed file, correct form" $'cat > /tmp/m.txt <<\'EOF\'\nt\n\nCloses #33, closes #35\nEOF\ngit commit -F /tmp/m.txt' 0

# ...and the message heredoc must survive the strip, or the hook is decorative.
run "pr body via cat substitution" "gh"" pr create --body \"\$(cat <<'EOF'
Closes #33, #35
EOF
)\"" 2

run "commit heredoc still read" "git commit -F - <<'MSG'
t

Closes #33, #35
MSG" 2

exit $fail
