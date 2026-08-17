# Decision — the log masking mechanism is the driver's switch, not a scrubber (2026-08-17)

Closes the mechanism question `#64` opened. The security record said tokens are
"masked in logs" and named no mechanism; `#64` asked for one before auth
shipped, and auth shipped first.

## What the risk actually is, checked rather than assumed

`#64` cited two paths. Only one of them exists.

- **Our own exception messages** carry nothing sensitive today. Every `error(…)`
  and custom exception in `api/src/main` interpolates a class name, a
  registration id, or nothing. The cited
  `IllegalArgumentException("invalid session token: $token")` **is not in the
  codebase**, and would not leak if it were: `SessionToken.toString()` already
  renders `verifier=***`, and the only raw composition is `cookieValue`, which
  goes into a cookie and never into a message.
- **Foreign exception messages do leak, and it is confirmed live.** The `#93`
  audit found the 5xx funnel logging pgjdbc's message verbatim, which carries
  `Detail: Key (lower(email))=(…) already exists.` A unique violation quotes
  the conflicting value.

So the mechanism has one real target, and designing a general scrubber for the
imagined one would have been the expensive mistake.

## The call

**Turn the values off at the driver: `logServerErrorDetail=false`.**

PostgreSQL puts row values in the error response's `DETAIL` field, and pgjdbc
copies `DETAIL` into the exception message because `logServerErrorDetail`
defaults to true. Switching it off removes those fields from the message. The
message keeps what an incident needs — `duplicate key value violates unique
constraint "ux_app_user_email"` — and loses only which value collided.

**Scope it precisely, because the first draft of this record did not.** What
the switch removes is the four context fields — `DETAIL`, `HINT`, `POSITION`,
`WHERE`. It is *not* "the values". `getNonSensitiveErrorMessage()` keeps
`MESSAGE`, and `MESSAGE` quotes the input for a whole class of failures:
`invalid input syntax for type numeric: "010-1234-5678"`, the same for `uuid`,
out-of-range for `integer`, an unknown enum label. Every one of those is
reachable where a request- or file-supplied string is bound into a typed
column — **the import path**, which `api/AGENTS.md` already calls the one v1
operation that is easy to get badly wrong. The consequence is a rule, not a
patch: **validate in the application before a value reaches a typed column; a
cast is not a validator.**

Why this rather than scrubbing the rendered message:

- **It maps to where the data actually is.** `DETAIL` is not a heuristic about
  what looks like an email; it is the field Postgres puts values in. A regex
  over messages is a guess with false negatives, and a false negative here is a
  guest's phone number in a log.
- **It is a constraint, not a mechanism** — one property, no code to maintain,
  nothing to keep in step with new exception types.
- **It generalises to data we have not stored yet.** The same switch covers the
  first `guest` unique constraint and every 축의금 column later, with no edit.

**It is set in every profile, not only prod.** A masking behaviour that differs
in dev is one nobody tests; this repo already learned that a config only
rehearsed in prod is not rehearsed.

**It is asserted at startup on the resolved value**, like the Flyway pins —
`SPRING_DATASOURCE_*` in a deploy platform outranks every yml, so a guard that
reads a committed file cannot see the configuration that actually runs.

## The other pipes, and what pins them

Auditing this change found that closing the exception-message route left a
**larger** one open, and that this record had claimed the leak closed while it
stood. Both are now shut, and the second half is the more important lesson.

- **pgjdbc's own logger prints more than the switch removes.** In
  `QueryExecutorImpl` the driver logs the server error message at `FINEST`
  with a rendering that includes `DETAIL` **unconditionally** —
  `logServerErrorDetail` is consulted on the *next* line, when the exception is
  constructed, so it never reaches that statement. The same logger prints bound
  parameters verbatim: 하객 names, phone numbers, emails, the session-token
  hash. Boot bridges JUL into logback, so one `LOGGING_LEVEL_ROOT=TRACE` during
  an incident restores everything we removed and more.
- **Spring's `Resolved [<exception>]` DEBUG line** carries Jackson messages
  that quote the offending input. Environment-only today, same class.

**So the four logger pins move from a committed-file sweep to a startup
guard.** `org.hibernate.SQL`, `org.hibernate.orm.jdbc.bind`,
`org.hibernate.orm.jdbc.extract` and `org.postgresql` are refused at DEBUG or
below on the *resolved* level. This is not a new principle — it is the one this
repo already wrote down for Flyway, quoted in this very record, applied to the
pipe next door: **the environment outranks every yml, and a check that reads a
committed file cannot see the configuration that actually runs.** The sweep
stays as the earlier detector; it is no longer the only thing.

The guard refuses `DEBUG or below` rather than each library's exact level,
because `FINEST` and `TRACE` are not promises those libraries made us — what is
stable is that nothing above DEBUG carries per-row data. It pins
`org.postgresql`, the package, so a driver upgrade that moves the trace cannot
step out from under it.

## What stays as it is

- **The throwable stays attached to the 5xx log.** `#67` now has two mutants
  that go red if either producer drops it: the client is told nothing, so the
  exception is the whole diagnosis. Masking must never be implemented by
  logging less of the exception.
- **`org.hibernate.orm.jdbc.bind` / `extract` stay `OFF`.** They close the
  other pipe for the same data and are unaffected by this.
- **No masking layer over our own messages.** The rule is that a sensitive
  value never goes into one, which is true today and is cheap to keep true. A
  scrubber would make it look handled and let the next hand write
  `"guest ${guest.phone} not found"`.

## The cost, stated

An incident loses "which value collided" from the log. For the one path where
that mattered — a duplicate first login — `#93` no longer produces an incident
at all, and the constraint name still says which axis. If a future path needs
the value, it reads the row deliberately rather than harvesting it from an
error message.

## What this does not decide

- **The 4xx log line** — `#65`, which is what makes a 401/404 spike visible at
  all.
- **What a field-level 400 may say** — `#63`. The response half is masked and
  tested today, and Spring's own 4xx body for an unreadable request is a fixed
  string. `#63` is precisely the change that would put a rejected value into a
  response body, so it inherits the rule above: name the field, never quote the
  value.
- **Whether the import path validates before casting** — `#21`/`#22` own that,
  under the rule this record states.

Refs `#64`, `#67`, `#65`, `#93`, `notes/2026-07-30-decision-network-security.md`
