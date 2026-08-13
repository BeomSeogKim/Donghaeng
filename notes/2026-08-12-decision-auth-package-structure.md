# Decision — `auth/` splits in two, and a test is the boundary (2026-08-12)

Prompted by `auth/` reaching twenty-four files in one directory during `#37`, and
by a finding that `api/AGENTS.md` asserted a guarantee the compiler does not
provide. This is the structure fifteen domains will copy, so both halves are
recorded.

## The finding: `internal` enforces nothing here

The rule file said:

> Kotlin `internal` is the default visibility inside a domain package

**Kotlin has no package-private, and `internal` is *module*-scoped.** `api/` is a
single Gradle module (`rootProject.name = "donghaeng-api"`, no subprojects), so
every `internal` type in `auth/` is visible to every other package in the module.
The sentence read as though a compiler was holding a line it has never heard of.

It was already being crossed: `RealConfigurationBootTest` imports
`CorsProperties`, an `internal` type, from outside its package. Harmless in
itself, and proof the barrier does not exist.

The *second* sentence — cross-domain access goes through a declared contract,
never straight into another domain's rows — is the rule we actually want. It kept
its meaning and gained a check.

## The split

```
auth/                  the composition root — controller, filter chain, MVC wiring
auth/login/            the round trip and the account: app_user, oauth_identity,
                       provider profiles, the OAuth handlers
auth/session/          the token, the cookie, the row, expiry, the CurrentUser resolver
```

**`login` depends on `session`; `session` depends on nothing.** That direction was
read off the code rather than chosen: `LoginService` needs a session issued, and
nothing in `session/` knows an account exists — `user_session.user_id` is a
`Long`, not a mapping. It is also the direction that matters for what comes next,
because `#89` grows `login/` and `#5` grows `session/`, and neither should be able
to reach into the other while doing it.

The composition root may depend on both. Neither may depend on the root — wiring
knows the parts, parts do not know the wiring, and the reverse is how a package
graph acquires its first cycle.

## What left `auth/` entirely

`CorsPolicy` and `FrontendProperties` moved to `config/`. Neither is about
authentication: one decides which origins may call **the API**, the other is the
web origin this deployment has. They were in `auth/` because `auth/` was the only
domain package that existed when they were written, which is not a reason.

`config/` was chosen over a new package because the alternative name for
"cross-cutting web configuration" collides with this repo's own `web/` tree, and
`config/` already means "configuration this application refuses to start without
or misbehaves under". A reader looking for the CORS policy looks there.

## The boundary is a test, and a module split is the option not taken

A Gradle module per domain would be a real compiler barrier. It is deliberately
not built: one module with one domain does not have the problem module boundaries
solve, and the build complexity would be paid every day from now against a
benefit that arrives when a second domain does. **Read the absence as a decision,
not an oversight.**

`ArchitectureTest` (ArchUnit) is the barrier instead, plus `SourceShapeTest` for
what bytecode cannot see. **ArchUnit over Konsist**: the primary requirement is
dependency direction, and ArchUnit reads *actual* dependencies out of bytecode
where a source-based tool reads import statements — a fully-qualified reference
has no import and would be invisible. Konsist's file- and naming-convention
assertions are the thing it does better, and those are two hand-written
assertions rather than a second architecture-testing dependency.

**Three of the rules name no packages**, which is the property that makes them
outlive today's tree: the domain is derived from the package name, so `#7`'s
`wedding/` and `#11`'s `guest/` are covered by rules written before they exist.
Verified, not assumed — a throwaway `com.donghaeng.wedding` reaching into
`auth.login`'s repository was refused.

### The trap this nearly shipped as decoration

The three custom rules were first written as `noClasses().should(condition)`.
**ArchUnit negates the condition in that form**, so a custom condition that adds
`violated` events contributes nothing: every violation becomes a satisfied event
and the rule reports success on code that breaks it. All three were inert, and the
deliberate violations used to check them were being caught by the *cycle* rule
instead — which is exactly the shape of "a check nobody has watched fail is not a
check", one level up. The working form is `classes().should(not…)`.

Worth stating because it will be met again: ArchUnit reads `build/classes`, so a
verification run must clear the output directory. An incremental compile can
leave a mutated class behind and make the *next* run report a violation that is
no longer in the source.

## What the checks cannot see, stated so nobody reads green as covered

Three gaps were found by a reviewer running mutations against the first version
of these rules. Each is real, none is closed, and the first is the reason this
section exists at all.

**`const val` is invisible to every bytecode rule.** Kotlin inlines it at the call
site, so a sibling package reaching for `SecurityConfig.CALLBACK_PATH` leaves
nothing referencing `SecurityConfig` in its own class file — a real dependency
that ArchUnit provably could not see. The record argued bytecode over source
because a fully-qualified reference has no import; this is the converse, and it
was unstated. Closed by conversion (`val` compiles to a field read) and by a
`SourceShapeTest` rule forbidding a non-private `const val`. **`inline` functions
have the same property and are not closed** — they cannot be forbidden by fiat.

**Reaching into another domain's SERVICE is not caught.** The persistence rule
names entities and repositories, and `WeddingProbe(private val users: AppUserService)`
passes. `api/AGENTS.md` says only the Controller and a declared contract are
`public`, and with `internal` now correctly admitted to enforce nothing, **that
sentence has no mechanism behind it.** It is a convention, held by review.

**The persistence heuristic is a heuristic.** It matches `@Entity` and a simple
name ending in `Repository`. `@MappedSuperclass`, `@Embeddable`, and any
repository named otherwise are missed. Harmless while `auth/` is the only domain;
`#17`/`#80`'s aggregation repositories are where a miss stops being cosmetic and
becomes a wrong number.

## "It is mechanically checked, so the prose goes" — hollow twice

The rules-about-rules say a mechanically checkable rule belongs in the check and
then *not* also in prose. Applied twice in this stop, it removed a rule both
times.

**`api/AGENTS.md` said "never a layer bucket".** It was deleted on the grounds
that `SourceShapeTest` replaced it. It did not: the check fires only when a
persistence or configuration type shares a file, so appending an unrelated class
to `GoogleProfile.kt` — the exact defect that justified splitting `AppUser.kt` —
stayed green. Kotlin's convention permits several declarations per file when they
are *closely related*, and relatedness is a judgement no regex has.

The rule to draw from it, and it is the general one: **before deleting prose,
state the case the check does not cover and check whether it is the case the
prose was written about.** A check that replaces the special case and drops the
general rule leaves the rule nowhere. The prose is back, narrowed to the
judgement, and `SourceShapeTest`'s own comment names what it misses.

## The rules that were verified, and how

Every rule was checked by breaking it and reading **which test fired** — not by
watching the suite go red, which was itself the trap. Three separate mistakes
were caught this way and none by reading:

- three custom conditions inert under `noClasses().should(...)`, with the cycle
  rule catching the deliberate violations instead;
- the cycle rule naming `auth` under a comment claiming it derived the pair, so a
  mutual cycle between two inner packages of a *future* domain passed both levels;
- a guard test whose justification — "an empty import satisfies every rule" — was
  false, since ArchUnit 1.4.1 defaults `failOnEmptyShould` to true and an empty
  import fails all six.

**ArchUnit reads `build/classes`.** A verification run must clear the output
directory, or an incremental compile leaves a mutated class behind and the *next*
run reports a violation that is no longer in the source. That cost an hour once.

## What this does not decide

- **Not the other domains' internal shape.** `wedding/` and `guest/` may be flat;
  `auth/` split because it grew two clusters with different reasons to grow, not
  because two packages are a target.
- **Not the test layout.** The `auth/` tests still sit in `com.donghaeng.auth` and
  exercise the area end to end; only production code moved.
