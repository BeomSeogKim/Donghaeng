#!/usr/bin/env bash
# Blocks a commit or PR whose closing trailer names more than one issue.
#
# `Closes #33, #35` closes #33 and silently leaves #35 open. GitHub requires the
# keyword once per issue. Nothing goes red when this happens — the tracker just
# goes quietly wrong, which is the single failure the issues-as-tracker
# mechanism was chosen to avoid (notes/2026-08-08-decision-work-tracking.md).
# It was found by hand on #83, after the fact.
#
# Wired as a PreToolUse hook on Bash in .claude/settings.json. Exit 2 blocks the
# call and shows stderr to the agent. Tested by closes-guard.test.sh.
#
# Note the inversion versus merge-gate.sh: that hook strips heredoc bodies
# because prose is not something the shell runs. Here the heredoc body IS the
# subject — a commit message is exactly what we must read — so the whole command
# string is scanned, and the *gate* is instead on the command being one that
# writes a message.

set -uo pipefail

command=$(jq -r '.tool_input.command // empty') || {
  echo "closes-guard: cannot read the tool input, so refusing rather than" >&2
  echo "assuming this command carries no closing trailer." >&2
  exit 2
}
[ -n "$command" ] || exit 0

# A heredoc that writes a FILE is dropped, against the rule above.
# `cat > audit.html <<'HTML' … HTML` carries a document, and a document about
# this rule quotes the broken form on purpose — which blocked the writing of one
# on 2026-08-24. merge-gate.sh learned the same lesson from its own commit
# message; this hook had not been given the fix.
#
# But a file can BECOME a message: `cat > /tmp/m.txt <<EOF … EOF` followed by
# `git commit -F /tmp/m.txt` is a commit message that never appears on a command
# line. The first cut of this strip was line-scoped and let exactly that through
# — the #83 failure, reintroduced by the fix for a false positive. So the target
# of every file-write heredoc is read off its opener, and a target that is later
# consumed by -F / --body-file / --file is not a document at all.
consumed=$(printf '%s\n' "$command" |
  grep -oE '(-F|--file|--body-file)[[:space:]=]+[^[:space:]"'"'"';&|]+' |
  sed -E 's/^(-F|--file|--body-file)[[:space:]=]+//' | tr '\n' ' ')

runnable=$(printf '%s\n' "$command" | awk -v consumed=" $consumed " '
  BEGIN { delim = ""; strip = 0 }
  {
    if (delim != "") {
      line = $0
      sub(/^[ \t]+/, "", line)
      if (line == delim) { delim = ""; strip = 0; next }
      if (strip) next
      print
      next
    }
    if (match($0, /<<-?[ \t]*['"'"'"]?[A-Za-z_][A-Za-z0-9_]*['"'"'"]?/)) {
      d = substr($0, RSTART, RLENGTH)
      sub(/^<<-?[ \t]*/, "", d)
      gsub(/['"'"'"]/, "", d)
      delim = d

      # A file sink, and not a line that is itself writing a message —
      # `gh pr create --body "$(cat <<EOF … EOF)"` is how every PR body in this
      # repo is written and its heredoc IS the message.
      strip = ($0 ~ /(^|[;&|(])[ \t]*(cat|tee)[ \t][^|]*>/) &&
              ($0 !~ /git[ \t]+commit|gh[ \t]+pr[ \t]/) ? 1 : 0

      # ...and not a file that gets handed to a message flag further along.
      if (strip && match($0, />>?[ \t]*[^ \t|;&<>]+/)) {
        target = substr($0, RSTART, RLENGTH)
        sub(/^>>?[ \t]*/, "", target)
        if (index(consumed, " " target " ") > 0) strip = 0
      }
    }
    print
  }')

# Only commands that can actually close an issue, in a command position, so
# `grep "Closes #1, #2" notes/` is a mention and not a commit.
#
# `gh pr edit` is here because a trailer added to a PR body AFTER the PR exists
# closes just as silently as one written at creation, and `gh pr edit` is on the
# allow list so nothing else stands in front of it. The issue and comment forms
# are NOT here: GitHub closes issues from a PR description and from commit
# messages only, so an issue body or a comment closes nothing — blocking them
# would only refuse legitimate prose, and this repo's issues discuss this very
# rule.
if ! printf '%s\n' "$runnable" | grep -Eq \
  '(^|[;&|(]|\bthen\b|\bdo\b|\belse\b)[[:space:]]*(git[[:space:]]+commit|gh[[:space:]]+pr[[:space:]]+(create|edit))\b'; then
  exit 0
fi

# A real trailer starts its line. Prose that *describes* the mistake reads
# "`Closes #33, #35` closes #33 and leaves #35 open" — inline, and usually
# backticked, so requiring line-start plus rejecting a leading backtick keeps
# this file, its tests, and the notes records committable.
# The test is not "more than one issue on the line" — `Closes #33, closes #35`
# is the correct fix and must pass. It is "more issue references than closing
# keywords", which is exactly what GitHub drops on the floor.
offenders=$(printf '%s\n' "$runnable" | awk '
  {
    line = $0
    sub(/^[ \t]+/, "", line)
    # A one-line `--body "Closes #40, #41"` puts the trailer mid-line. Cut up to
    # the quote that opens the message so it is judged like any other trailer;
    # prose further along the line is untouched and still not line-start.
    sub(/^.*(--body|-m)[ \t]*=?[ \t]*["'"'"']/, "", line)
    sub(/^[ \t]+/, "", line)
    if (line ~ /^`/) next

    low = tolower(line)
    if (low !~ /^(closes?|closed|fix|fixes|fixed|resolves?|resolved)[ \t]+#[0-9]+/) next

    keywords = gsub(/(^|[^a-z])(closes|closed|close|fixes|fixed|fix|resolves|resolved|resolve)[ \t]+#/, "&", low)
    refs     = gsub(/#[0-9]+/, "&", low)
    if (refs > keywords) print "  " line
  }')

[ -n "$offenders" ] || exit 0

{
  echo "Blocked: a closing keyword names more than one issue."
  echo
  echo "$offenders"
  echo
  echo "One keyword closes exactly one issue. GitHub reads the first reference"
  echo "after the keyword and ignores the rest, so the others stay open with"
  echo "nothing going red."
  echo
  echo "Repeat the keyword, or split across lines:"
  echo "    Closes #33, closes #35"
  echo "    Closes #33"
  echo "    Closes #35"
  echo
  echo "(notes/2026-08-08-decision-work-tracking.md)"
} >&2

exit 2
