#!/usr/bin/env bash
# Blocks `gh pr merge` while the PR's checks are not green.
#
# The rule it enforces is "a red check is never merged"
# (notes/2026-08-08-decision-build-workflow.md). GitHub would normally enforce
# it server-side, but branch protection is not available on a private repo on
# the free plan, so this is the replacement — with the hole that it only covers
# merges initiated through this tool. See notes/2026-08-08-decision-merge-gate.md.
#
# Wired as a PreToolUse hook on Bash in .claude/settings.json. Exit 2 blocks the
# call and shows stderr to the agent.

set -uo pipefail

command=$(jq -r '.tool_input.command // empty')

# Match the invocation, not the words. A plain substring test blocked this
# hook's own commit, because the message *described* the rule — and every
# commit message or notes edit that mentions it would have done the same. Two
# narrowings, in order:
#
#   1. Drop heredoc bodies. `git commit -F - <<'MSG' ... MSG` carries prose as
#      part of the command string, and prose is not something the shell runs.
#   2. Require a command position — start of a line, or straight after a shell
#      separator. `echo gh pr merge` is a mention; `&& gh pr merge` is a merge.
#
# Both are deliberately conservative: they can only ever *reject* text that the
# shell would not execute as a merge.
runnable=$(printf '%s\n' "$command" | awk '
  BEGIN { delim = "" }
  {
    if (delim != "") {
      sub(/^[ \t]+/, "", $0)
      if ($0 == delim) delim = ""
      next
    }
    if (match($0, /<<-?[ \t]*['"'"'"]?[A-Za-z_][A-Za-z0-9_]*['"'"'"]?/)) {
      d = substr($0, RSTART, RLENGTH)
      sub(/^<<-?[ \t]*/, "", d)
      gsub(/['"'"'"]/, "", d)
      delim = d
    }
    # A "(" glued to the end of a bare word is a call or a rule string, not a
    # subshell — `Bash(gh pr merge*)` is a permission rule in settings.json, and
    # reading that file used to trip this hook. Replace that paren with a space
    # so the command-position test below no longer sees a delimiter. A real
    # subshell has "(" at the start or after whitespace or an operator, so this
    # cannot hide one.
    line = $0; out = ""
    while (match(line, /[A-Za-z0-9_]\(/)) {
      out = out substr(line, 1, RSTART) " "
      line = substr(line, RSTART + RLENGTH)
    }
    print out line
  }')

if ! printf '%s\n' "$runnable" |
  grep -Eq '(^|[;&|(]|\bthen\b|\bdo\b|\belse\b)[[:space:]]*gh[[:space:]]+pr[[:space:]]+merge\b'; then
  exit 0
fi

# `gh pr merge 42` targets a specific PR; a bare `gh pr merge` targets the
# current branch's. Ask about the same PR the command would merge, not whichever
# one the branch happens to point at.
pr=$(printf '%s' "$runnable" | sed -n 's/.*gh pr merge[[:space:]]\{1,\}\([0-9]\{1,\}\).*/\1/p' | head -1)

# `gh pr checks` exits 0 only when every check has concluded successfully; 8
# means some are still pending, anything else means failing or absent.
checks=$(gh pr checks ${pr:+"$pr"} 2>&1)
status=$?

if [ "$status" -eq 0 ]; then
  exit 0
fi

{
  echo "Merge blocked: this PR's checks are not green."
  echo
  echo "$checks"
  echo
  if [ "$status" -eq 8 ]; then
    echo "Some checks are still running. Wait for them rather than merging."
  fi
  echo "A red check is never merged — not 'unrelated', not 'fix it after'."
  echo "(notes/2026-08-08-decision-build-workflow.md)"
} >&2

exit 2
