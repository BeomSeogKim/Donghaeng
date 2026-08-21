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

Jackson does not hand this over for free — a nullable property is `null` for both
cases — so it is a mechanism: `com.donghaeng.json.Patch`, three cases (`Absent`,
`Cleared`, `Set`) filled by a deserializer whose `getNullValue` is what makes an
explicit `null` observable at all. A sealed hierarchy rather than a nullable payload,
so that every reader is an exhaustive `when` and "the caller sent null" has a name.
It lives in a package **underneath** the domains, listed in `ArchitectureTest`'s
`SUBSTRATE`, because `wedding/` and `guest/` will both read bodies with it.

**A member with no cleared state wears `@NotCleared`** and answers `null` with a 400
`VALIDATION_FAILED`. It is not silently ignored: ignoring it would mean this
endpoint's `null` means "clear" on one member and "nothing" on another, which is the
rule drifting inside a single DTO. What refuses it is a constraint and not a service
check, for the standing reason — **a cast is not a validator**
(`2026-08-17-decision-log-masking-mechanism.md`), and what the edge does not refuse
the column refuses as a masked 500.

**An empty body `{}` is a legal no-op**: 200, nothing written, and `updated_at` does
not move. A row reported as touched when it was not is a lie an audit read would
believe.

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
  "누가 보증인원을 바꿨어?" is currently unanswerable. That is a gap this record names
  and does not close — it needs an issue, not a table invented here.
- **Whether an unknown JSON member is refused.** Unchanged and still API-wide: a
  member this DTO does not declare is ignored.
- **The seat name** (`#175`) and the 참석 토글 (`#13`) — both inherit §1 and §3, and
  neither is written here.

Refs `#173`, `#8`, `#12`, `#13`, `#14`, `#175`,
`2026-08-21-decision-the-headcount-endpoint.md`,
`2026-08-20-decision-mutation-response-envelope.md`,
`2026-08-20-decision-row-concurrency-and-the-audit-trail.md`,
`2026-08-22-decision-the-couples-two-seats.md`,
`2026-08-17-decision-first-domain-endpoint-shape.md`,
`2026-08-17-decision-log-masking-mechanism.md`.
