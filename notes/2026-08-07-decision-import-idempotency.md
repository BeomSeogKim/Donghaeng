# Decision — import is idempotent, and the file has no opinion about attendance (2026-08-07)

Closes the knot that had been blocking screen design since 2026-08-06: **how the
import conflict screen behaves when one file brings in dozens of rows at once.**

It turned out to be the wrong question. Two founder corrections dissolved most
of it.

## Correction 1 — the risk is re-import, not overlap

I had been designing for **ambiguity**: the same relative appearing in both sets
of parents' files, requiring a human to judge whether two similar rows are one
person. That is why the screen had to survive forty conflicts at once.

The founder's actual worry was **the same Excel file being imported more than
once**. That is not ambiguity at all. The rows are not *similar*, they are
*identical*. Nobody needs to judge anything; the system needs to notice and not
do it twice. It is an idempotency problem wearing a conflict problem's clothes,
and it has a completely different solution.

Overlap between the two parents' files does happen, but it is a handful of
relatives — an edge case, not the main event.

## Correction 2 — the spreadsheet lists attendees, so it never states attendance

The table parents fill in carries **참석 인원** — the people who are coming and
how many each brings. There is no attendance column and there are no 불참 rows,
because nobody writes "this person isn't coming" on a list of people they are
inviting.

Two consequences, and the second is the important one:

**Presence in the file means 참석** — which needs no new mechanism, because
expected attendance already defaults to 참석 (2026-08-06).

**A returning file is a stale name list, not a fresh assertion.** When a parent
adds ten names and re-sends, the file still contains the guest the couple has
since marked 불참. That is not the parent claiming they will attend — it is a row
nobody deleted. So **import never touches an existing guest's attendance at
all.** Not overwrite, not even alert: there is nothing to disagree about.

## The rule the founder set

> Do not overwrite the couple's edits. Alert them, and let them choose.

Two guards make that rule quiet enough to live with:

**Silence is not disagreement.** A blank cell or an absent column is not a claim.
Import only raises a question when the file *asserts* a different value. Without
this, a 180-row re-import would raise a question about every field the couple
has ever touched — which is exactly the punishment the one-screen review was
meant to avoid.

**A resolved question is not asked again.** When the couple keeps their own
value, that decision is remembered, so the same file does not raise the same
alert on every future import.

Given correction 2, the only field import and the couple can disagree about is
**참석 인원**. Meal counts, accessibility needs, and attendance never come from a
file at all.

## What import does

    file hash matches a previous import  → do not process; say when it was imported
    every field identical                → skip silently, count it in the summary
    same person, 인원수 differs           → ask: 내 값 유지 / 파일 값으로
    identity unclear                     → ask: 합치기 / 따로 두기
    otherwise                            → create (참석, n명)

Comparison is by **multiset, not row**: two different guests can legitimately
share a name and a group. If the file has two 김민수 and the ledger has two, both
are skipped; if the ledger has one, one is new.

**The summary is the screen, not the conflict list.**

    192행 — 새로 10 · 이미 있는 분 180 · 확인 필요 2

That is the whole reason the original problem dissolved: of forty apparent
conflicts, thirty-eight were never conflicts. They were the same rows arriving
again.

## The template we hand out

**이름 · 관계 · 참석 인원 · 연락처(선택)** — four columns.

- **관계** cannot be dropped. Without it the couple hand-classifies several
  hundred people, and group aggregation — a v1 feature — dies on arrival.
- **연락처** stays optional but is worth asking for: a parent-filled sheet
  usually carries the whole number, which makes identity matching far more
  reliable than the vendor-email channel (2026-08-06).
- **참석 여부 is deliberately NOT a column.** Adding it asks the parents for one
  more thing *and* makes the spreadsheet a second channel for attendance, which
  would resurrect the conflicts this decision just removed.

## What this changes upstream

`ConflictRow` was built as a full field-by-field diff. It is now two questions
with two buttons each, and the component shrinks accordingly. The card's own
warning — that the list's behaviour at scale was unsolved — comes off, because
the scale never materialises.

## 관계 — constrain at the source, map what leaks through

> **SUPERSEDED IN PART 2026-08-11**
> (`2026-08-11-decision-import-row-rejection.md`). A row whose 관계 is not one
> of the seven categories **does not import** — the rest of the file does, and
> the couple fixes those rows. So the three subsections below that describe
> what happens to a leaked value are dead: **the shipped synonym table, the
> map-by-distinct-value screen, and "unmapped imports as 기타 with the raw text
> in the free label."** The couple is a reviewer, not a courier: they open the
> file before uploading and can classify their own 이모. What survives is the
> first half of this section — the dropdown in the template, and the rule that
> the importer must not assume the column is clean.

Decided the same day. The seven fixed categories go into the template **as a
dropdown** (Excel data validation) on the 관계 column, with the free label as a
separate, freely-typed column. Classification happens where the knowledge is: a
parent knows whether 이모 is 친척, and we would only be guessing.

**But data validation is advisory, not enforced.** Pasting over a cell defeats
it, and opening the file in Google Sheets or a phone viewer can drop it
entirely. So the dropdown reduces the unmapped case; it never eliminates it, and
the importer must not assume the column is clean.

### Map by distinct value, not by row

The guard that keeps the fallback from becoming punishment. If 40 rows say
"이모", the couple answers **once** and all 40 follow:

    "이모"      (40행)  →  [ 친척 ▾ ]
    "회사 동료"  (28행)  →  [ 직장동료 ▾ ]

A messy 200-row file usually holds 10–20 distinct 관계 values, so this is ~15
decisions rather than 200. Same shape as the multiset rule above: the unit of
the question is never the row when a coarser unit will do. Answered mappings are
remembered for that wedding and not asked again.

### A shipped synonym table, not a learned one

Most of those 15 disappear behind a **static table we author and ship** —
이모 · 고모 · 삼촌 · 외삼촌 · 조카 → 친척; 회사 · 직장 · 팀 → 직장동료. This is a
dictionary, not inference, and it is ours rather than derived from anyone's
data, so it crosses no wedding boundary. When it is wrong the couple corrects
it, which costs one tap on a value rather than on a row.

### Unmapped never blocks

An unmapped 관계 imports as **기타 with the raw text preserved in the free
label**, and the question is asked on the review screen afterwards. Holding the
rows back would contradict the standing rule that "not sure" must never block
an import (2026-08-06) — reclassification is lossless here, so deciding later
costs nothing, while stalling the import costs everything.

### Consequence: the template is .xlsx

CSV cannot carry data validation, so **the template we hand out is .xlsx** while
**the parser accepts both .xlsx and .csv** — someone will inevitably open it in
Google Sheets and export CSV. Earlier notes say "CSV import"; this supersedes
that on the export side only.

## Still open

- [ ] Storing the file hash: which entity holds it, and does the "already
      imported" screen offer a force-import escape hatch (for a couple who
      deleted rows and genuinely wants to re-run)?
- [x] ~~The initial contents of the shipped synonym table.~~ Closed 2026-08-11
      by `2026-08-11-decision-import-row-rejection.md` — there is no table.
