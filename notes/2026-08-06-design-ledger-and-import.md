# Design — groups, import workflow, ledger details (2026-08-06)

Second record of the day. Closes most of what
[2026-08-06-decision-v1-scope-and-meals.md](2026-08-06-decision-v1-scope-and-meals.md)
and [2026-08-05-design-meal-headcount.md](2026-08-05-design-meal-headcount.md)
left open, and adds one workflow that turned out to change the design: the couple
hands a template out and collects it back.

## 1. Groups — seven fixed categories plus a free label

    가족 · 친척 · 사촌 · 혼주 손님 · 친구 · 직장동료 · 기타

- **가족** — parents, grandparents, siblings.
- **친척** — extended relatives.
- **사촌** — kept separate from 친척 because cousins are peers, and are seated and
  handled differently.
- **혼주 손님** — the parents' own guests.

Every guest carries the fixed category **plus a free label** — `친구 / "대학교
동아리 친구들"`, `가족 / "외조부모"`. **v1 aggregation splits by category only.**
Aggregating on the free label would fracture on typing variants ("대학동아리" vs
"대학 동아리") and produce dirty numbers.

### Why 가족 is one bucket rather than three

The founder's first draft split 부모님 / 형제 and then hit 조부모, which fit
nowhere. That is the failure mode of a fine-grained fixed list — 증조부모, a
spouse's grandparents, and step-siblings queue up right behind it. Every category
added creates a new boundary to be unsure about.

Size distribution argues the same way. 혼주 손님, 친구, and 직장동료 run to dozens
or a hundred each; every family category is single digits. Splitting a four-person
bucket three ways buys nothing when the point of a group is to break the headcount
into pieces worth looking at. The free label carries whatever detail the couple
wants without adding a boundary.

### 혼주 손님 is the addition that matters

In Korean weddings the parents' own guests are often close to half the total.
Without a category for them they all land in 기타 — making the largest single block
of the wedding the unlabelled one, and group aggregation meaningless. They are also
seated separately, which will matter when seating lands.

## 2. Import is a workflow, not a file upload

Founder, 2026-08-06: the couple needs **a template from us first**, so they can
hand it to both sets of parents and collect the filled copies back.

That changes the design. The guest list arrives as **several files at different
times**, not one, and the same person will appear in two of them — a relative both
parents know. So import has to deduplicate.

- Rows with no conflict import silently.
- Conflicting rows are collected onto **one review screen**, not a modal per row.
  Ten modals in sequence is punishment.
- The **existing matching pipeline** (side → normalized name → phone) is reused, so
  identity is still judged in exactly one place. It survives as logic; see
  [2026-08-06-decision-drop-response-model.md](2026-08-06-decision-drop-response-model.md)
  — the pipeline runs, its results are consumed on screen, and nothing is persisted.
- **"Not sure" must never block.** An undecided row imports as a separate guest and
  is flagged. Merging is lossless in this model, so deciding later costs nothing —
  whereas forcing a decision stalls the whole import.

Side benefit: a parent-filled spreadsheet usually carries the **whole phone
number** rather than the last four digits, so import matches more reliably than the
vendor-email channel does.

### Correction to this morning's claim

"The review queue effectively disappears" was half right. Matching still runs in v1
in **two** places — vendor-email paste and import. Neither one *queues*: both
resolve on a screen the couple is already looking at. What disappeared is the
queue, not the matching.

## 3. Login — merge by email, tolerate its absence

> **NARROWED 2026-08-11** (`2026-08-11-decision-baseline-schema-calls.md` §A).
> Merging by email survives, but only on an email the provider **asserts as
> verified** — Kakao returns `is_email_verified` as a separate field and can
> hand back an unverified address, Naver's is user-editable, and merging on
> either grants the whole ledger with no token and no expiry. An unverified
> address is not stored at all, so this section's "no key to merge on, accounts
> stay separate" branch is now also where unverified emails land. The
> no-account-linking call below is unchanged and was reaffirmed.

The same person arriving via a second provider is merged into one account by email.

But Kakao and Naver both treat email as an **optional consent scope**, so it may
not arrive at all. Then there is no key to merge on and the accounts stay separate.
Rather than build account-linking for v1, the **login screen remembers which
provider was used last** and says so. It doesn't survive a device change; accepted.

## 4. Defaults confirmed (inferences on 2026-08-05, decided now)

- **Expected attendance defaults to 참석.** The couple corrects it to 불참 when they
  hear otherwise, so starting from 참석 costs them fewer taps.
- **Expected meal count defaults to the expected party size.**

## 5. Ledger filters

**Side and attendance state** is enough for v1.

## 6. Meal ownership, confirmed

- The **meal type list** belongs to the `Wedding`.
- A **guest's meal counts** belong to the ledger entry — it is an attribute of that
  guest, not of the response that produced it.

## 7. The ledger and the headcount are one screen

Confirmed. Tapping attendance moves the number in place.

The couple's actual loop is "scan the list, tap what you heard, see what it does to
the number". Splitting that into two screens turns one action into tap → navigate →
check → return — at which point a spreadsheet with a SUM in the next column does
the same job, and our reason to exist goes with it.

**This is the first fixed point of the screen design.**

## Still open

- [ ] **Screen and flow design** — including how the import review screen behaves
      when a file brings in dozens of rows at once.
- [ ] Whether 유아식 counts toward the venue's 보증인원.
- [ ] When the couple configures meal types — onboarding, or on demand.
