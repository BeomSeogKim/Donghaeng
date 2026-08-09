#!/usr/bin/env bash
# Blocks any database client command aimed at a non-loopback host.
#
# The counterpart to "the founder applies DDL by hand"
# (notes/2026-08-09-decision-schema-ownership.md): if a real database is only
# ever changed deliberately, in a terminal, by a person reading the statement
# first, then nothing else may reach one.
#
# WHITELIST, not a blacklist. It does not know which host is production and
# never will — a list of known-production hosts grows a hole every time an
# environment is added. Loopback is allowed; everything else is refused.
#
# Blocking the founder's own agent sessions from production DDL is the point,
# not a side effect. Run those statements in a terminal instead.
#
# Wired as a PreToolUse hook on Bash in .claude/settings.json. Exit 2 blocks the
# call and shows stderr to the agent. Tested by db-guard.test.sh.

set -uo pipefail

# A refusal control that allows everything when its own dependency is missing is
# pointed the wrong way. If this hook cannot read its input, it refuses.
command -v jq >/dev/null 2>&1 || {
  echo "db-guard: jq is not available, so this hook cannot inspect the command. Refusing." >&2
  exit 2
}

command=$(jq -r '.tool_input.command // empty')

if [ -z "$command" ]; then
  echo "db-guard: empty or unreadable tool input. Refusing rather than assuming it is harmless." >&2
  exit 2
fi

# Drop heredoc bodies before matching — but only when the heredoc is DATA.
#
# merge-gate.sh strips every heredoc, which is right there: its false positives
# are commit messages. Here the same rule opens a hole, because `bash <<EOF …
# psql -h prod … EOF` makes the heredoc the payload rather than prose. So the
# body is dropped only for a known text sink (a commit message, a file being
# written), and kept for anything else — including any command this hook has
# never heard of. Unknown means analysed, not ignored.
runnable=$(printf '%s\n' "$command" | awk '
  BEGIN { delim = ""; strip = 0 }
  {
    if (delim != "") {
      line = $0
      sub(/^[ \t]+/, "", line)
      if (line == delim) { delim = ""; next }
      if (strip) next
      print
      next
    }
    if (match($0, /<<-?[ \t]*['"'"'"]?[A-Za-z_][A-Za-z0-9_]*['"'"'"]?/)) {
      d = substr($0, RSTART, RLENGTH)
      sub(/^<<-?[ \t]*/, "", d)
      gsub(/['"'"'"]/, "", d)
      delim = d
      strip = ($0 ~ /git[[:space:]]+commit|git[[:space:]]+tag|cat[[:space:]]*>|tee[[:space:]]/) ? 1 : 0
    }
    print
  }')

# Clients that can execute DDL/DML against a server. `flyway` is here because
# the whole point of the decision is that it no longer touches a real database.
clients='psql|pg_dump|pg_dumpall|pg_restore|pgcli|flyway'

# Match the client as a WORD, anywhere, with an optional path prefix — not at a
# shell command position. Requiring a command position was a blacklist of
# prefixes wearing a whitelist's clothes: `sudo psql`, `env PGHOST=… psql`,
# `/opt/homebrew/bin/psql`, `docker exec -i pg psql` and a heredoc feeding an
# interpreter all walked straight through it. Every one of those was found by
# probing the hook, none by reading the regex.
#
# The cost is deliberate and is where this hook differs from merge-gate.sh: a
# sentence that merely MENTIONS a client with a remote host is refused too.
# merge-gate optimises for precision because commit messages discuss merging
# constantly; here a false block costs a rephrase and a miss costs a production
# database, so the asymmetry points the other way.
if ! printf '%s\n' "$runnable" |
  grep -Eq "(^|[[:space:];&|(])([^[:space:];&|(]*/)?($clients)\b"; then
  exit 0
fi

# An indirection we cannot resolve is refused rather than assumed local. A
# service name or a Flyway config file names a target that lives somewhere else
# entirely, so "no host on the command line" stops meaning "local socket".
# A shell variable in a host position is the same problem: the hook sees `$X`,
# never what it expands to. `psql "$DATABASE_URL"` is the common shape and it is
# genuinely unresolvable from here — so it is refused, not waved through.
indirect=$(printf '%s\n' "$runnable" | grep -oE \
  "PGSERVICE=[^[:space:]]+|PGSERVICEFILE=[^[:space:]]+|--?configFiles?=[^[:space:]]+|(^|[[:space:]\"'])service=[^[:space:]\"']+|(-h|--host=?|PGHOST=|PGHOSTADDR=|host=)[[:space:]]*[\"']?\\\$[A-Za-z_{]|($clients)[[:space:]]+[\"']?\\\$[A-Za-z_{]")

if [ -n "$indirect" ]; then
  {
    echo "Blocked: a database client whose target is named indirectly —"
    printf '%s\n' "$indirect" | sed 's/^[[:space:]]*/  /'
    echo
    echo "A service entry or a config file can point at any host, so this hook"
    echo "cannot see whether it is loopback. Unresolvable means refused, not"
    echo "assumed safe. (notes/2026-08-09-decision-schema-ownership.md)"
  } >&2
  exit 2
fi

# Every way a host reaches one of these clients: a URI, -h/--host, PGHOST,
# PGHOSTADDR, or a keyword/value conninfo string (`psql "host=… dbname=…"`).
# A command with no host at all talks to the local socket, which is loopback by
# definition and therefore allowed.
hosts=$(printf '%s\n' "$runnable" | grep -oE \
  'postgres(ql)?://[^[:space:]"'"'"']+|jdbc:postgresql://[^[:space:]"'"'"']+|(-h|--host=?)[[:space:]]*[A-Za-z0-9._-]+|PGHOST=[A-Za-z0-9._-]+|PGHOSTADDR=[A-Za-z0-9.:_-]+|(^|[[:space:]"'"'"'])host=[A-Za-z0-9._-]+')

remote=""
while IFS= read -r ref; do
  [ -n "$ref" ] || continue
  # Trim first: the conninfo and `-h host` forms are captured with their leading
  # separator, and every prefix strip below is anchored.
  host=$(printf '%s' "$ref" | tr -d '[:space:]"'"'"'')
  host=${host#*://}                 # strip scheme, and userinfo if present
  host=${host##*@}
  host=${host%%/*}                  # strip path
  host=${host%%\?*}                 # strip query
  host=${host%%:*}                  # strip port
  host=${host#PGHOSTADDR=}
  host=${host#PGHOST=}
  host=${host#--host=}
  host=${host#-h}
  host=${host#--host}
  host=${host#host=}
  host=$(printf '%s' "$host" | tr -d '[:space:]')
  [ -n "$host" ] || continue
  case "$host" in
    localhost|127.0.0.1|::1|0.0.0.0) ;;
    *) remote="$remote $host" ;;
  esac
done <<EOF
$hosts
EOF

if [ -z "$remote" ]; then
  exit 0
fi

{
  echo "Blocked: a database client aimed at a non-loopback host —$remote"
  echo
  echo "DDL and queries against a real database are applied by hand, in your own"
  echo "terminal, by a person who read the statement first. An agent session is"
  echo "not that. This is a whitelist: only loopback is allowed, so a host this"
  echo "hook has never heard of is refused rather than assumed safe."
  echo "(notes/2026-08-09-decision-schema-ownership.md)"
} >&2

exit 2
