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

# A gate that waves everything through when its own dependency is missing is
# pointed the wrong way; db-guard.sh has said so since it was written.
command=$(jq -r '.tool_input.command // empty') || {
  echo "merge-gate: cannot read the tool input. Refusing rather than assuming" >&2
  echo "this command is not a merge." >&2
  exit 2
}

GH=${GH:-gh}
GIT=${GIT:-git}

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

# Two spellings of one act. `gh pr merge` is the one anybody types; the REST
# call underneath it is `PUT /repos/{owner}/{repo}/pulls/{n}/merge`, and reaching
# for that walked straight through this hook until 2026-08-24 — matched against
# a live PR it returned 0 without ever asking about a check. db-guard.sh had
# already written the lesson down: matching a spelling is not matching an act.
#
# The limit is honest and stated: `gh api --method PUT "$url"` names no path
# this hook can read. `gh api` is deliberately absent from the allow list, so
# that shape still stops at a permission prompt.
merges=$(printf '%s\n' "$runnable" |
  grep -Eco '(^|[;&|(]|\bthen\b|\bdo\b|\belse\b)[[:space:]]*gh[[:space:]]+pr[[:space:]]+merge\b')

if [ "$merges" -gt 1 ]; then
  {
    echo "Merge blocked: this command merges more than one PR."
    echo "Only the first can be checked, so the rest would go unexamined."
    echo "Run them one at a time."
  } >&2
  exit 2
fi

pr=""
if [ "$merges" -eq 1 ]; then
  # The positional argument is not necessarily first: `gh pr merge --squash 220`
  # is the same command as `gh pr merge 220 --squash`, and reading "digits
  # immediately after merge" found nothing in that form — then silently fell
  # back to whatever PR the CURRENT branch points at. This repo runs worktrees,
  # so that is routinely a different, greener PR (found in review, 2026-08-24).
  seg=$(printf '%s\n' "$runnable" | grep -o 'gh[[:space:]]\{1,\}pr[[:space:]]\{1,\}merge.*' | head -1)
  seg=${seg%%;*}; seg=${seg%%&*}; seg=${seg%%|*}
  for tok in $seg; do
    case "$tok" in
      gh|pr|merge|-*) continue ;;
      *[!0-9]*)       break ;;
      *)              pr=$tok; break ;;
    esac
  done
  # An empty $pr here is the bare `gh pr merge`, which really does mean the
  # current branch's PR. Both `checks` and `view` below resolve it the same way.
elif printf '%s\n' "$runnable" | grep -Eq '(^|[;&|(]|\bthen\b|\bdo\b|\belse\b)[[:space:]]*gh[[:space:]]+api\b' &&
     printf '%s\n' "$runnable" | grep -Eq -- '(-X|--method)[[:space:]=]+PUT' &&
     printf '%s\n' "$runnable" | grep -Eq '/merge\b'; then
  pr=$(printf '%s\n' "$runnable" | grep -o 'pulls/[0-9]\{1,\}/merge' | head -1 |
       sed 's|pulls/||; s|/merge||')
  if [ -z "$pr" ]; then
    {
      echo "Merge blocked: this is a merge call whose PR number this hook cannot"
      echo "read, so it cannot ask whether that PR's checks are green."
      echo "Use \`gh pr merge <n>\`, which it can."
    } >&2
    exit 2
  fi
else
  exit 0
fi

# `gh pr checks` exits 0 only when every check has concluded successfully; 8
# means some are still pending, anything else means failing or absent.
checks=$("$GH" pr checks ${pr:+"$pr"} 2>&1)
status=$?

if [ "$status" -ne 0 ]; then
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
fi

# Green. Everything left is one question with three parts, and one `gh pr view`
# answers all of them. It used to be four separate calls for fields a single
# call returns — seven network round trips before a merge.
meta=$("$GH" pr view ${pr:+"$pr"} \
  --json baseRefName,headRefName,headRefOid,files,comments,reviews 2>/dev/null)

base=$(printf '%s' "$meta" | jq -r '.baseRefName // empty' 2>/dev/null)
head=$(printf '%s' "$meta" | jq -r '.headRefName // empty' 2>/dev/null)
head_oid=$(printf '%s' "$meta" | jq -r '.headRefOid // empty' 2>/dev/null | tr 'A-Z' 'a-z')
repo=$("$GH" repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null)

# -- 1. Was the green against today's `main`?
#
# Nothing re-checks an open PR when `main` moves under it, and re-running is a
# trap because it replays the recorded merge SHA. It cost a red `main` on
# 2026-08-20 (#138); the rule was written the same day and three of the next
# eight merges went in stale anyway. AGENTS.md says a mechanically checkable
# rule belongs in a hook rather than in prose — this is the hook.
if [ -z "$repo" ] || [ -z "$base" ] || [ -z "$head" ]; then
  {
    echo "Merge blocked: cannot read this PR's base and head branches, so"
    echo "whether it is behind ${base:-main} is unknown."
    echo "Unknown is refused, not assumed current."
  } >&2
  exit 2
fi

behind=$("$GH" api "repos/$repo/compare/$base...$head" -q '.behind_by' 2>/dev/null)

case "$behind" in
  0) ;;
  ''|*[!0-9]*)
    {
      echo "Merge blocked: could not compare $head against $base."
      echo "Whether this branch is stale is unknown, and unknown is refused."
    } >&2
    exit 2 ;;
  *)
    {
      echo "Merge blocked: this branch is $behind commit(s) behind $base."
      echo
      echo "Its green check ran against a $base that no longer exists, and nothing"
      echo "re-checks a PR when the base moves under it. Re-running the check does"
      echo "not help — it replays the recorded merge SHA."
      echo
      echo "Rebase and push, let the check run again, then merge:"
      echo "    git fetch origin && git rebase origin/$base && git push --force-with-lease"
      echo
      echo "(notes/2026-08-20-decision-merge-order-gate.md)"
    } >&2
    exit 2 ;;
esac

# -- 2. Is this a diff the agent may merge at all?
files=$(printf '%s' "$meta" | jq -r '.files[]?.path // empty' 2>/dev/null)

if [ -z "$files" ]; then
  {
    echo "Merge blocked: could not list this PR's files, so whether it touches a"
    echo "reserved surface is unknown. Unknown is refused, not assumed ordinary."
  } >&2
  exit 2
fi

reserved=$(printf '%s\n' "$files" | "$(dirname "$0")/reserved-surfaces.sh")
status=$?

if [ "$status" -ne 0 ]; then
  {
    echo "Merge blocked: this PR touches a surface the founder merges personally —"
    printf '%s\n' "$reserved" | sed 's/^/  /'
    echo
    echo "Auth, sessions, tokens and migrations are carved out of agent merging"
    echo "because a mistake there is expensive and quiet. Everything else on this"
    echo "PR is fine; hand it over rather than splitting the change."
    echo
    echo "(notes/2026-08-24-decision-the-agent-merges-behind-a-gate.md)"
  } >&2
  exit 2
fi

# -- 3. Did a reviewer look at THIS, and say something about it?
#
# The other two were mechanical before the agent started merging. The condition
# that replaced the founder — "a reviewer has cleared it" — was the one left as
# prose, which AGENTS.md says is where a checkable rule goes to die.
#
# The verdict is matched against the head's TREE, not its commit id. Keying it
# to the commit was the first cut and it defeats itself: the merge-order rule
# above mandates a rebase of every open PR after every merge, a rebase always
# yields a new commit id, and the gate would then demand a fresh review of a
# diff that had not changed by one byte — several times a day, with the block
# message handing over the very line to paste. A control everyone learns to
# satisfy without performing is worse than a documented absence, because it
# reads as coverage. A content change still goes stale; a replay of the same
# content does not. (Found in review on #230.)
if [ -z "$head_oid" ]; then
  {
    echo "Merge blocked: cannot read this PR's head commit, so whether the"
    echo "review on it is current is unknown. Unknown is refused."
  } >&2
  exit 2
fi

# Reviews as well as comments: a verdict left through `gh pr review` or the web
# UI's Files-changed flow lands in a different list, and reporting "none" beside
# a review visibly sitting on the PR is a lie the hook can avoid.
#
# Each body is judged whole, because two things about it matter. A marker inside
# a fence is a quotation, not a verdict — this file already strips heredoc
# bodies for exactly that reason, and the new check had not been given the
# lesson. And a body that is ONLY the marker states a sha and nothing about a
# review.
markers=$(printf '%s' "$meta" |
  jq -r '[(.comments[]?.body // ""), (.reviews[]?.body // "")]
         | map(. + "\nxx-end-of-body-xx") | .[]' 2>/dev/null |
  tr 'A-Z' 'a-z' |
  awk '
    function flush_it() {
      if (sha != "" && said > 0) print sha
      sha = ""; said = 0; fenced = 0
    }
    /^xx-end-of-body-xx$/ { flush_it(); next }
    /^[ \t]*```/          { fenced = !fenced; next }
    fenced                { next }
    /^reviewed-at:[ \t]*[0-9a-f]{7,40}[ \t]*$/ {
      line = $0
      sub(/^reviewed-at:[ \t]*/, "", line)
      sub(/[ \t]*$/, "", line)
      sha = line
      next
    }
    /[^ \t]/ { said++ }
    END { flush_it() }
  ')

head_tree=$("$GIT" rev-parse "$head_oid^{tree}" 2>/dev/null)

cleared=""
for v in $markers; do
  case "$head_oid" in "$v"*) cleared=$v; break ;; esac
  if [ -n "$head_tree" ]; then
    vt=$("$GIT" rev-parse "$v^{tree}" 2>/dev/null)
    if [ -n "$vt" ] && [ "$vt" = "$head_tree" ]; then cleared=$v; break; fi
  fi
done

[ -n "$cleared" ] && exit 0

{
  echo "Merge blocked: no review is recorded against this PR's current content."
  echo
  echo "  head       $head_oid"
  if [ -n "$markers" ]; then
    echo "  recorded   $(printf '%s ' $markers)"
    echo
    echo "None of those name this head or a commit with the same tree. A review"
    echo "that predates a real change does not carry to it — though a rebase or"
    echo "an amend does carry, because the content is what was read."
  else
    echo "  recorded   (none)"
    echo
    echo "A verdict is a comment or a review with a line of its own reading"
    echo "Reviewed-at: <sha>, plus at least one more line saying what was found."
    echo "A marker inside a fence is a quotation and does not count."
  fi
  echo
  echo "Run the reviewer, act on what it says, then record the verdict:"
  echo "    gh pr comment ${pr:-<n>} --body \"Reviewed-at: $head_oid"
  echo ""
  echo "<what it found, and what was done about it>\""
  echo
  echo "(notes/2026-08-24-decision-the-agent-merges-behind-a-gate.md)"
} >&2

exit 2
