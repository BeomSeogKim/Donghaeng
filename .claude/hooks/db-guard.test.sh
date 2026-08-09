#!/usr/bin/env bash
# Exercises db-guard.sh: does it tell a loopback target from a remote one, and a
# command that runs a client from one that merely mentions it?
# Run: bash .claude/hooks/db-guard.test.sh
cd "$(dirname "$0")/../.." || exit 1

fail=0
run() {
  printf '%s' "$2" | jq -Rs '{tool_input:{command:.}}' | ./.claude/hooks/db-guard.sh >/dev/null 2>&1
  got=$?
  if [ "$got" = "$3" ]; then verdict="ok"; else verdict="MISMATCH"; fail=1; fi
  printf '%-38s exit %s  expected %s  %s\n' "$1" "$got" "$3" "$verdict"
}

# remote targets -> must block (2)
run "uri, remote host"      'psql postgres://app@db.example.com:5432/donghaeng -c "select 1"' 2
run "-h remote"             'psql -h db.internal -U app donghaeng' 2
run "--host= remote"        'pg_dump --host=10.0.0.5 donghaeng > dump.sql' 2
run "PGHOST remote"         'PGHOST=db.example.com psql -c "drop table guest"' 2
run "jdbc uri remote"       'flyway -url=jdbc:postgresql://prod.example.com/donghaeng migrate' 2
run "chained after &&"      'echo ok && psql -h db.example.com -c "select 1"' 2
run "remote pg_restore"     'pg_restore -h backup.example.com -d donghaeng dump.sql' 2

# loopback / local -> must pass (0)
run "local socket, no host" 'psql donghaeng -c "select 1"' 0
run "-h localhost"          'psql -h localhost -p 5432 donghaeng' 0
run "-h 127.0.0.1"          'pg_dump -h 127.0.0.1 donghaeng > /tmp/d.sql' 0
run "uri loopback"          'psql postgres://donghaeng_app@127.0.0.1:5432/donghaeng' 0

# Bypasses found by the security audit by probing the hook — every one of these
# was ALLOWED by the command-position version. They are the regression suite.
run "sudo prefix"           'sudo -u postgres psql -h db.example.com -c "drop table guest"' 2
run "env prefix"            'env PGHOST=db.example.com psql -c "select 1"' 2
run "absolute path"         '/opt/homebrew/bin/psql -h db.example.com -c "select 1"' 2
run "docker exec prefix"    'docker exec -i pg psql -h db.example.com -c "select 1"' 2
run "PGHOSTADDR"            'PGHOSTADDR=203.0.113.9 psql -c "select 1"' 2
run "PGSERVICE indirection" 'PGSERVICE=prod psql -c "select 1"' 2
run "keyword conninfo"      'psql "host=db.example.com dbname=x user=app"' 2
run "flyway config file"    'flyway -configFiles=prod.conf migrate' 2
run "variable host"         'psql -h $REMOTE_HOST -c "select 1"' 2
run "variable url"          'psql "$DATABASE_URL" -c "select 1"' 2
run "sealbox wrapping"      'sealbox run -p donghaeng -- psql "$DATABASE_URL"' 2
run "heredoc to interpreter" 'bash <<EOF
psql -h db.example.com -c "drop table guest"
EOF' 2

# not a client invocation at all -> must pass (0)
run "unrelated command"     'git status' 0
run "greps for the rule"    'grep -rn "psql" notes/' 0
# NOTE: a sentence mentioning a client AND a remote host is refused here, unlike
# merge-gate.sh which lets mentions through. Deliberate: a false block costs a
# rephrase, a miss costs a production database. Prose lives in heredocs anyway.
run "mention, remote host"  'echo "run psql -h db.example.com yourself"' 2
run "mention, no host"      'echo "use psql for this"' 0
run "heredoc prose"         "git commit -F - <<'MSG'
psql -h prod.example.com 로 직접 적용한다
MSG" 0
# This one is not hypothetical: it blocked the PR that introduced this hook.
run "PR body via cat heredoc" "gh pr create --body \"\$(cat <<'BODY'
훅은 flyway -configFiles=prod.conf 같은 간접 지정을 거부한다
BODY
)\"" 0
# ...but a heredoc piped into an interpreter is a payload, not prose.
run "cat heredoc piped to sh" 'cat <<EOF | bash
psql -h db.example.com -c "drop table guest"
EOF' 2

exit $fail
