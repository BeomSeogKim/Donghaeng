#!/usr/bin/env bash
# Enforces the AGENTS.md line budgets.
#
# The root file reached 908 lines because the rule for adding to it had no
# counterpart for taking things out (notes/2026-08-11-decision-agents-md-hierarchy.md).
# A budget was written the same day and nothing ran it, which is the same
# failure one level up — so this runs, from .githooks/pre-push and from CI.
#
# The numbers are not derived from anything. Official guidance is under 200
# lines per CLAUDE.md; the root here carries a whole product's standing truth
# and lands just above that. The value is in forcing the choice at the moment
# something is added, not in the number.
#
# Over budget, the fix is to compress or relocate BEFORE adding — to a subtree
# file, to the notes record, or to a hook.

set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1

declare -a BUDGETS=(
  "AGENTS.md:220"
  "api/AGENTS.md:280"
  "web/AGENTS.md:280"
  "design/AGENTS.md:280"
)

fail=0
for entry in "${BUDGETS[@]}"; do
  file="${entry%:*}"
  budget="${entry##*:}"

  # A budgeted file that vanished is a failure, not a pass. Silence here would
  # mean deleting the file is the cheapest way to satisfy its budget.
  if [ ! -f "$file" ]; then
    printf '  %-20s MISSING (budgeted at %s)\n' "$file" "$budget" >&2
    fail=1
    continue
  fi

  lines=$(wc -l < "$file" | tr -d ' ')
  if [ "$lines" -gt "$budget" ]; then
    printf '  %-20s %4s / %s  OVER by %s\n' "$file" "$lines" "$budget" "$((lines - budget))" >&2
    fail=1
  else
    printf '  %-20s %4s / %s\n' "$file" "$lines" "$budget"
  fi
done

if [ "$fail" -ne 0 ]; then
  {
    echo
    echo "AGENTS.md is over budget."
    echo
    echo "Compress or relocate before adding. A rule belongs at exactly one"
    echo "level: both trees → root, one tree → that subtree, something"
    echo "mechanically checkable → a hook or a test. Rationale belongs in"
    echo "its notes/ record."
  } >&2
  exit 1
fi

exit 0
