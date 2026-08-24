#!/usr/bin/env bash
# Reads file paths on stdin, one per line. Exit 2 and name them if any lands on
# a surface the founder merges personally; exit 0 otherwise.
#
# The agent merges its own PRs from 2026-08-24. These four surfaces are carved
# out of that because a mistake on them is both expensive and quiet — the two
# properties the product values single out (정직함·믿음직함: a wrong number, a
# lost edit, a leaked contact). Everywhere else a bad merge announces itself.
#
# It is a file rather than a regex inside merge-gate.sh so the list has exactly
# one home and a suite can exercise it without a live green PR.
# (notes/2026-08-24-decision-the-agent-merges-behind-a-gate.md)
set -uo pipefail

# 1. auth, oauth and sessions      2. every migration      3. the invite token
#
# Scoped to `api/` on purpose: the client can be wrong about a token and the
# damage is a failed request, while the server being wrong about one is the
# whole of it.
RESERVED='^api/src/[^/]+/kotlin/com/donghaeng/auth/|^api/src/main/resources/db/migration/|InviteToken'

hits=$(grep -E "$RESERVED" || true)
[ -n "$hits" ] || exit 0

printf '%s\n' "$hits"
exit 2
