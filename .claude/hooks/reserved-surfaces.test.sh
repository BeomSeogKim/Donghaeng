#!/usr/bin/env bash
# Exercises .claude/hooks/reserved-surfaces.sh — the list of surfaces an agent
# does not merge for itself. A carve-out nothing tests is a carve-out that
# quietly stops carving.
cd "$(dirname "$0")/../.." || exit 1

fail=0
run() {
  printf '%s\n' "$2" | ./.claude/hooks/reserved-surfaces.sh >/dev/null 2>&1
  got=$?
  if [ "$got" = "$3" ]; then verdict="ok"; else verdict="MISMATCH"; fail=1; fi
  printf '%-46s exit %s  expected %s  %s\n' "$1" "$got" "$3" "$verdict"
}

# --- reserved -> the founder merges these (2)
run "a migration"        'api/src/main/resources/db/migration/V7__x.sql' 2
run "session service"    'api/src/main/kotlin/com/donghaeng/auth/session/SessionService.kt' 2
run "oauth handler"      'api/src/main/kotlin/com/donghaeng/auth/oauth/OAuthLoginSuccessHandler.kt' 2
run "security config"    'api/src/main/kotlin/com/donghaeng/auth/SecurityConfig.kt' 2
run "an auth test"       'api/src/test/kotlin/com/donghaeng/auth/session/SessionCookiesTest.kt' 2
run "the invite token"   'api/src/main/kotlin/com/donghaeng/wedding/InviteToken.kt' 2
run "invite token test"  'api/src/test/kotlin/com/donghaeng/wedding/InviteTokenSiblingTest.kt' 2
run "one reserved among many" 'web/src/pages/LedgerPage.tsx
docs/api-spec.md
api/src/main/resources/db/migration/V7__x.sql
notes/README.md' 2

# --- ordinary work -> the agent merges it (0)
run "a guest endpoint"   'api/src/main/kotlin/com/donghaeng/guest/GuestController.kt' 0
run "the wedding entity" 'api/src/main/kotlin/com/donghaeng/wedding/Wedding.kt' 0
run "the invite service" 'api/src/main/kotlin/com/donghaeng/wedding/WeddingInviteService.kt' 0
run "a screen"           'web/src/pages/LedgerPage.tsx' 0
run "the spec"           'docs/api-spec.md' 0
run "a notes record"     'notes/2026-08-24-decision-x.md' 0
run "these hooks"        '.claude/hooks/merge-gate.sh' 0
run "nothing at all"     '' 0

# --- the word alone is not the surface
run "a note about auth"  'notes/2026-07-30-decision-network-security.md' 0
run "a web token helper" 'web/src/lib/invite.ts' 0

exit $fail
