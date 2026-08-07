# Design — screens and flow (2026-08-07)

Design step ③, the last thing between the model and building. It had been
blocked since 2026-08-06 on the import conflict screen; that knot dissolved
earlier today (`2026-08-07-decision-import-idempotency.md`), and this is what
was assembled from the decisions once nothing was in the way.

## There is essentially one screen

The ledger is home. Everything else is either a sheet that opens over it or a
flow that leaves and comes back.

**No tab bar, no home dashboard.** Any such structure creates the question
"where do I go to see the number", and this product must not have that
question. The number is on the screen the couple is already looking at.

Three intake paths — file import, vendor-email paste, direct entry — all
converge on the ledger, and the main action never leaves it: tapping an
attendance chip moves the number in place, no navigation.

    로그인 → [웨딩 만들기 · 최초 1회] → 원장
    원장 ⟲ 참석 칩 탭 → 숫자 260ms 이동 (화면 전환 없음)
    원장 ↔ 하객 상세 시트 · 하객 추가 · 설정
    파일 올리기 → 요약 → 검토(질문 3종) ─┐
    벤더 이메일 붙여넣기 → 매칭 확인 ────┼→ 원장
    직접 입력 ──────────────────────────┘

## Eight screens, one of which is the product

| Screen | What it does | Primary device |
|---|---|---|
| 로그인 | 네이버 · 카카오 · 구글; remembers the last provider used | mobile |
| 웨딩 만들기 | date and the couple's names. Once, and nothing more | mobile |
| **원장 + 인원수** | the whole product — scan, search, filter, tap, the number | both |
| 하객 상세 시트 | attendance, party size, per-type meal counts, 배려사항, group | mobile |
| 하객 추가 시트 | direct entry; the only required field is a name | both |
| 임포트 | template download → file → summary → review | PC |
| 이메일 붙여넣기 | paste vendor mail → parse → confirm matches | PC |
| 설정 | wedding info, 보증인원, meal types, partner invite | both |

## Two layouts, one codebase — confirmed 2026-08-07

The founder asked for web and mobile as two versions from the start. Settled at
**one codebase with two layouts**, which is the 2026-07-30 client-strategy
decision reaffirmed — now with the layouts actually drawn rather than promised.

What is shared: **one route, one data layer, one token set.** What splits: the
layout and `GuestRow`. The reason to hold that line is not tidiness — the moment
the same number is computed in two places, the two versions can disagree about
it, and never-wrong numbers is the product's first value.

### PC exists because of the aggregation rail, not because it is wide

This is the finding that makes the two-layout decision concrete. The desktop
ledger carries a right rail with **group and meal breakdowns**; mobile does not,
because there is no room and adding it pushes the list down.

- **Mobile's job**: answer "몇 명" instantly and fix one guest in one tap.
- **PC's job**: sit down and work the list — aggregation, contact numbers,
  import, email paste.

Without the rail the desktop version is just a wide phone screen, and then it
has no reason to exist. Contact number is a PC-only column for the same reason:
needed when tidying up after an import, noise when scanning on a phone.

Density rules apply identically to both: 60px rows on mobile, 44px on desktop,
both flush and hairline-separated, never cards.

## Ledger anatomy: number → tools → list

That order is the couple's actual order — see the number, find the person, tap.
The number is pinned at the top and stays visible while scrolling, because the
couple must see *which* figure moved when they tap.

Filters are **side and attendance state only**. Group is deliberately not a
filter: a group is something you read in the aggregate, not an axis for
narrowing the list.

Rows carry two tap targets with different destinations — the chip toggles in
place, the row opens the detail sheet. That is another reason the chip has a
44px floor.

## Three things drawing the flow exposed — all confirmed

Raised as my calls while drawing; **all three confirmed by the founder the same
day.**

**1. Search is in v1.** The settled filter set (side + attendance) narrows a
list; it does not *find a person*. The real trigger is "김영수 못 온대", and what
that needs is two syllables, not a 400-row scroll. Without search the couple
scans every time, which is no better than the spreadsheet. It is the second
most-used control in the product, after the attendance chip — and the only one
of these three that widened v1 scope.

Search is a **Field variant, not a new component**, and the filter chips are a
**Tag with a selected state**; the ten-component inventory still holds. The
ledger toolbar sits between the number and the list: search takes the remaining
width, filters sit beside it.

**2. 보증인원 is not asked at onboarding.** Couples sign up before booking a
venue, and at that point the number does not exist. Asking for an unknown on the
first screen is where people quit. **The ledger works completely without it**,
with the comparison (여유 / meter) simply not rendering until it is set — it is
edited later in 설정 · 웨딩 정보. Onboarding stops at date and names.

**3. Meal types are not asked at onboarding either** — this also closes the
question parked on 2026-08-06. The default is a single type, so the simple case
configures nothing, and the moment a couple first meets meal types is when a
guest needs 유아식. Adding a type belongs *there*, in the detail sheet, not
behind a settings screen they must go find.

## Smaller calls made while drawing

- **"양식 내려받기" lives inside the import screen.** Import is a workflow, not
  an upload: the couple needs the template from us before they can hand it to
  their parents, so the first thing the import screen does is put it in their
  hands.
- **Partner invite is in settings, not onboarding.** Starting alone and inviting
  later is the natural order, and onboarding must not block on it.
- **The ⋯ menu holds import, email paste, and settings.** None is frequent
  enough to spend screen furniture on; the bottom of the screen belongs to the
  list.

## Still open

- [ ] Storing the import file hash: which entity holds it, and whether the
      "already imported" screen offers a force-import escape hatch.
- [ ] The initial contents of the shipped 관계 synonym table.
- [ ] Retention policy for `GuestChange`.
- [ ] Whether 유아식 counts toward the venue's 보증인원.
