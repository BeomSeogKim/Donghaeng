# Decision — two small calls from the open-question walk (2026-08-11)

Founder's calls, in the same conversation that produced
[2026-08-11-decision-import-row-rejection.md](2026-08-11-decision-import-row-rejection.md).
Both are small, and both came out of asking what actually happens rather than
what the schema implies.

## A — deletion is not the trigger for destroying a guest's contact data

Narrows `#34`. Does **not** close it: the retention period itself is still open.

**The question.** `guest_change` stores the old and new value of every changed
field, so a guest's phone number survives in it after the guest row is
soft-deleted. Deleting a 하객 therefore does not delete their phone number. The
audit log cannot simply be purged along with the guest — a deletable audit log
is not an audit log (2026-08-10).

**The domain fact that settled it.** Asked why a couple deletes a guest, the
founder's answer was: *usually because that person can't come, occasionally
because it was entered wrong.* Both, in the same button.

**The call.** Because those two cannot be told apart from the outside,
**deletion cannot be the trigger for destroying contact data.** Destroying a
phone number is irreversible, and hanging an irreversible act on a signal that
means two different things is the wrong shape:

- **오타로 지운 것** — the person may not exist. Erasing the trace costs nobody
  anything.
- **못 와서 지운 것** — a real guest who was really invited and really answered,
  and who may still send 축의금. This is data, not a mistake.

**What it means in practice: nothing changes.** Soft delete already keeps the
data and already lets the import ask "되살릴까요?". The point of recording this
is that a future retention job must not take deletion as its cue.

**A guardrail against over-reading this.** It is tempting to conclude that the
delete button is doing 참석 여부's job and needs redesigning — the ledger's
whole question is "who is coming and who is not", so a couple who deletes 불참
guests turns it into a list of only the attending. The founder's response is
the correct one and is recorded so it is not re-litigated: **we cannot know
which it is, and it varies by couple.** We are not designing around a motive we
made up. Soft delete means the ledger holds up either way, so there is nothing
to fix until real couples show us otherwise.

**Still open in `#34`:** what the actual trigger and period are. That depends
on how long after the wedding a couple keeps opening the ledger, which neither
of us knows yet.

## B — 유아식 does not adjust the 식대 인원; it gets its own line

Closes `#35`.

**The question, open since 2026-08-06.** Does a child eating 유아식 count toward
보증인원? Getting it wrong means the couple commits to a wrong number and pays
for it.

**The founder's answer.** 유아식 is usually priced differently, but this is not
ours to resolve — show 유아 인원 separately and leave the arithmetic to the
couple.

**The call.** **We never add or subtract 유아식 from the number shown against
보증인원.** The 식대 인원 stays exactly what
[2026-08-05-design-meal-headcount.md](2026-08-05-design-meal-headcount.md) §1
defines — every guest's meal count, whatever type it is. The meal-type
breakdown shows 유아식 as its own count beside it, and a couple whose contract
prices children differently reads both numbers and applies their own contract.

**Why this is not a dodge.** It is the same rule the 08-05 note already
established, applied one level down: **보증인원 is the venue's number, and we
never recommend it.** We do not know a given venue's buffer, and we do not know
its child pricing either. Deciding "유아식 counts" or "유아식 does not count"
globally would make the number wrong for half our couples, and a wrong number
here is money. Showing both honestly is the whole job.

**Mechanism — this is already built into the plan.** 유아식 is a meal type the
couple configures (2026-08-06), the per-guest counts are `#14`, and the
breakdown is `#18`. Nothing new is needed. What `#18` should carry from this is
that the breakdown is **not only a PC-rail affordance** — it is how a couple
resolves their own contract, so 유아 인원 has to be reachable on mobile too.
Where exactly is `#18`'s call.

**Not in scope.** That 유아식 is priced differently points at a 정산 feature.
That is far past v1 and is not opened here.
