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

## What this does not decide

- **Not the other domains' internal shape.** `wedding/` and `guest/` may be flat;
  `auth/` split because it grew two clusters with different reasons to grow, not
  because two packages are a target.
- **Not the test layout.** The `auth/` tests still sit in `com.donghaeng.auth` and
  exercise the area end to end; only production code moved.
