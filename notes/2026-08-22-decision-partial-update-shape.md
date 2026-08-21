# Decision — what a partial update means, and where one lives (2026-08-22)

`#173`, the backend half of `#8`. It is the product's **first `PATCH`** — there was
not a `@PatchMapping` or a `@PutMapping` in the tree before it — so what it settles
is not one endpoint but the shape `#12` (하객 상세 수정), `#13` (참석 토글), `#14` and
`#175` inherit. Four things, each of which would otherwise be re-argued by whoever
writes the second one.

It is also the endpoint **보증인원 enters the product through.** The column has been
in `V1` since the beginning and `GET /weddings/{weddingId}/headcount` has published
it since `#151`, but nothing ever wrote it — `POST /weddings` does not accept one —
so until today the comparison the headcount screen is built around could not render
for any couple.

## 1. 생략과 `null`은 다른 뜻이다 — and on a create they are not

`POST /weddings/{weddingId}/guests` says in `docs/api-spec.md` that an omitted member
and one sent as `null` mean **exactly the same thing**. That is true there and stays
true: on a create both take the column's default, so the distinction has nothing to
be about.

On an update it cannot hold. The two readings are "leave what is stored alone" and
"store no value", and they are different writes:

- **Omitted → not written.** Not "written with what it already had" — not written at
  all, so it is not in the UPDATE statement.
- **`null` → cleared**, where the member has a cleared state.

**Why omission has to mean untouched, rather than the whole editable state being
replaced.** The alternative is a `PUT` carrying both members, and this repo has
already written down what that costs, in the `@DynamicUpdate` KDoc on `Wedding`: a
request that meant to set 보증인원 but sends both members blind-writes 예식일 from
whatever its form loaded. `wedding` has **no `guest_change` trail**, so the
overwritten value is not recoverable, and
`2026-08-20-decision-row-concurrency-and-the-audit-trail.md`'s "last write wins is
accepted" was written about columns that do have one. The couple are two accounts
with the same screen open; a partial update confines a collision to a field both of
them actually typed into.

**What holds this is `WeddingUpdateStatementTest`, against the SQL that is issued** —
and until review nothing did. The contract test that reads "a member that is not sent
is left alone" makes two PATCHes in two transactions, so the second reloads a row
already holding the value and writes it back; a handler that assigned every column
passes it identically, as does deleting `@DynamicUpdate`. The property is about one
statement, so it is asserted on one statement, and dropping `@DynamicUpdate` turns it
red.

**And one thing this design makes unnecessary to defend, stated because the first
version of this record over-claimed it.** The blind full-column write a PUT would do
**is not expressible in this service**: `Patch.Absent` carries no value, so an omitted
member has nothing to be overwritten with, and Hibernate dirty-checks by value anyway
— assigning a column the value it already holds never reaches the UPDATE. Measured:
reverting the per-member comparison turns exactly one case red, and the statement it
produces is `update wedding set updated_at=? where id=?`, which is the `updated_at`
rule below and not this one. **So what a test can hold here is "the UPDATE does not
NAME the untouched column"**, which is held; the remaining risk is a client sending a
member it did not mean to change, and that is a client contract published in
`docs/api-spec.md`, not something the server can check.

Jackson does not hand this over for free — a nullable property is `null` for both
cases — so it is a mechanism: `com.donghaeng.json.Patch`, three cases (`Absent`,
`Cleared`, `Set`) filled by a deserializer whose `getNullValue` is what makes an
explicit `null` observable at all. A sealed hierarchy rather than a nullable payload,
so that every reader is an exhaustive `when` and "the caller sent null" has a name.
It lives in a package **underneath** the domains, listed in `ArchitectureTest`'s
`SUBSTRATE`, because `wedding/` and `guest/` will both read bodies with it.

### Wrapping a payload in `Patch` removes a protection every other DTO has

The most load-bearing fact about this type, found in review and stated here because
nobody would otherwise know it. `jackson-module-kotlin` null-checks the **constructor
parameter**, and that parameter is `Patch<LocalDate>` — non-null, and satisfied by a
`Patch`. **The type argument is erased**, so nothing checks the payload:

```
Plain(weddingDate: LocalDate)  {"weddingDate":""} -> KotlinInvalidNullException -> 400
Body(date: Patch<LocalDate>)   {"date":""}        -> Patch.Set(value = null)    -> 500
```

Jackson returns `null` from `deserialize` — not only from `getNullValue` — for an
empty string, a blank string **and an empty array**, three spellings and not one, and
neither Boot nor `Jackson2ObjectMapperBuilder` changes that coercion. `Patch.Set`'s
payload is `T` with upper bound `Any?`, so Kotlin emits no check either. Observed
before the fix: `{"guaranteedHeadcount":""}` answered **200 and erased the 보증인원**
the couple had agreed with their venue, because every validator downstream reads
`(value as? Patch.Set)?.value ?: return true` — which says "absent or cleared" and
silently also says "a `Set` carrying null".

**So `PatchDeserializer` refuses it: a `Patch` payload that cannot be read is
`MALFORMED_REQUEST_BODY` — never `Cleared`, and never `Set(null)`.** Not mapped to
`Cleared`, because that would make `""` a second spelling of "clear 보증인원", an
intent nobody expressed and a clear nobody asked for; `Patch`'s whole thesis is named
states and no unnamed conventions. `PatchTest` holds all three coercions against the
Boot-configured mapper, for both a date member and an integer member — the coercion
is per-type, so one passing says nothing about the other.

**A member with no cleared state wears `@NotCleared`** and answers `null` with a 400
`VALIDATION_FAILED`. It is not silently ignored: ignoring it would mean this
endpoint's `null` means "clear" on one member and "nothing" on another, which is the
rule drifting inside a single DTO. What refuses it is a constraint and not a service
check, for the standing reason — **a cast is not a validator**
(`2026-08-17-decision-log-masking-mechanism.md`), and what the edge does not refuse
the column refuses as a masked 500.

**Forgetting `@NotCleared` fails silent, so a sweep holds it.** The service's `when`
reads `Patch.Cleared -> Unit`, the request answers **200 having written nothing**, and
nothing else notices — no 500, no leak, a contract lie. `PatchMemberSweepTest` refuses
any `Patch` member that carries neither the annotation nor an entry in an allowlist
**naming the domain reason it may be emptied**, in the shape `ResolvedPrincipalTest`'s
`PUBLIC` and `ScopelessWeddingEndpointTest`'s `SCOPELESS` already use. Verified by
deleting the annotation: the sweep names the member and goes red.

**A constraint on a `Patch` member is written against `Patch`, not against the
payload — and that is forced, not chosen.** The tidy alternative is Hibernate
Validator's `ValueExtractor`, which would make `Patch<@Min(1) Int>` work and delete
both hand-rolled validators. **It cannot be written in Kotlin as this module is
built** — tried and measured, not assumed. `ValueExtractor<Patch<@ExtractedValue *>>`
is a Kotlin syntax error (a star projection cannot be annotated), and the concrete-type
spelling compiles but is refused by Hibernate Validator at startup (`HV000203: fails to
declare the extracted type parameter using @ExtractedValue`), because under this
build's compiler arguments `javap` shows `@Min` on a type argument landing **only in
`@Metadata`, never as a JVM `RuntimeVisibleTypeAnnotations` attribute** — the only
place Hibernate Validator looks.

**"Impossible" would be one word too strong, so it is not the word.** Adding the
experimental `-Xemit-jvm-type-annotations` to `freeCompilerArgs` does make Kotlin
2.2.21 emit it (`RuntimeVisibleTypeAnnotations … TYPE_ARGUMENT(0)`, verified), which
would leave only a Java source file for the extractor itself. **It is expensive, not
impossible, and the answer is still no**: an experimental `-X` flag changes how every
class in `api/` is compiled, and a Java source file in a Kotlin-only tree is a second
toolchain, both bought to delete two small validators.

**So `#12` should budget five `Patch`-typed validators as a standing cost of the
wrapper** — `@NotBlank`, three `@Size` and `@Min(1)` on `CreateGuestRequest` do not
carry across, and this is the paragraph that exists so nobody re-litigates it there
and reaches for the flag.

**An empty body `{}` is a legal no-op**: 200, nothing written, and `updated_at` does
not move — and neither does a member **resent unchanged**, which is compared rather
than assumed for the same reason. A row reported as touched when it was not is a lie
an audit read would believe.

## 2. 보증인원 can be cleared, and that is a domain answer

`null` clears it. **미설정 is a real state, not the absence of an answer** — a couple
signs up long before they book a venue — and it is a state a couple can arrive back
at: a contract that fell through, a venue changed, a number typed into the wrong
field. A product that can only ever move 보증인원 from unset to set would leave those
couples looking at a number their venue never agreed to, and **보증인원 is the venue's
number, never ours** (root `AGENTS.md`): we may not keep a stale one standing in for
one we do not have.

Cleared and never-set are **the same state and answer the same way** — the headcount
omits the member (`2026-08-21-decision-the-headcount-endpoint.md` §2). We do not
record that a couple once had a number; that would be an audit fact, and `wedding`
keeps none.

**예식일 cannot be cleared**, because a wedding always has a date — the column is NOT
NULL and that is the model, not a storage detail.

## 3. The endpoint writes a wedding and is served from `guest/`

**Every mutation response carries the recomputed aggregate** (root `AGENTS.md`), and
changing 보증인원 changes what `GET .../headcount` answers, so this response carries
`headcount` — the same object that endpoint returns, computed **in the same
transaction as the write**. That is the whole point: the couple must not have to
refetch a number they just typed.

That requirement decides the package, and not by preference. `guest/` already depends
on `wedding/` for `WeddingScope` and 측, and the aggregate lives in `guest/` because
**the arrow between these two packages is already spent**
(`2026-08-21-decision-the-headcount-endpoint.md` §3). A controller in `wedding/`
naming `HeadcountService` would close a package cycle `ArchitectureTest` refuses —
so **any endpoint that mutates a wedding-scoped resource and answers with the number
is assembled on the ledger's side of that arrow, whatever resource it writes.**
`#175`'s seat-name edit lands there for the same reason, and this is the sentence
that saves it re-deriving this.

What stays in `wedding/` is the write itself — `WeddingService.update`, the row, its
invariants and its clock. `guest/WeddingUpdateService` is the one `@Transactional`
both happen inside. The alternatives were weighed and both cost more than they buy:
a dependency inversion (an interface in `wedding/`, implemented in `guest/`) reopens
a merged placement decision and builds the ports-and-adapters layer
`2026-08-07-decision-backend-architecture.md` refuses; a third package holding one
controller breaks "packages are domain-based" with nothing behind it.

## 4. `WeddingResponse` does **not** gain `guaranteedHeadcount`

`docs/api-spec.md` and the `WeddingResponse` KDoc both predicted, before this stop,
that `#8` would add the member to the wedding's own shape. **It does not, and both
have been corrected in this change.**

The response of this endpoint is `{wedding, headcount}` and `headcount` already
carries 보증인원. Adding it to `wedding` too would put **one number in one response
twice**, which is the drift shape this repo refuses everywhere else — the two would
be equal until the day one of them was not. There is one spelling,
`headcount.guaranteedHeadcount`, here and in `GET .../headcount` alike.

The 설정 screen loses nothing: it already reads the headcount endpoint to prefill the
field, which is the endpoint that publishes 보증인원 beside the 식대 인원 it is read
against.

## What this does not decide

- **A product bound on 예식일.** Still nobody's decision but the founder's, and still
  open (`2026-08-17-decision-first-domain-endpoint-shape.md`). The PATCH refuses
  exactly what the column cannot store and nothing else.
- **An audit trail for `wedding`.** There is none: `guest_change` is the ledger's, so
  "누가 보증인원을 바꿨어?" is currently unanswerable. Named here and closed nowhere —
  it is `#179`, whose milestone is deliberately blank until the founder calls it v1 or
  post-v1. A table is not invented in this record.
- **Whether an unknown JSON member is refused.** Unchanged and still API-wide: a
  member this DTO does not declare is ignored. That is a different question from a
  **declared** member whose payload cannot be read, which §1 answers:
  `MALFORMED_REQUEST_BODY`.
- **The seat name** (`#175`) and the 참석 토글 (`#13`) — both inherit §1 and §3, and
  neither is written here.

Refs `#173`, `#8`, `#12`, `#13`, `#14`, `#175`,
`2026-08-21-decision-the-headcount-endpoint.md`,
`2026-08-20-decision-mutation-response-envelope.md`,
`2026-08-20-decision-row-concurrency-and-the-audit-trail.md`,
`2026-08-22-decision-the-couples-two-seats.md`,
`2026-08-17-decision-first-domain-endpoint-shape.md`,
`2026-08-17-decision-log-masking-mechanism.md`.
