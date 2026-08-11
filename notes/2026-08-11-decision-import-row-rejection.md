# Decision — a row that does not match the template is held back (2026-08-11)

Founder's call, in conversation. **Supersedes "Unmapped never blocks"** in
[2026-08-07-decision-import-idempotency.md](2026-08-07-decision-import-idempotency.md),
and deletes the shipped synonym table before it was ever written.

Third record of the day, and unrelated to the other two — this one is about
import, not the baseline schema.

## The call

A row whose 관계 value is not one of the seven categories **does not import**.
The rest of the file does. The couple sees the held-back rows, fixes them, and
uploads again. Alongside the upload control we state that the format has to
match.

Three things this removes, none of which had been built yet:

- **the static synonym table** we were going to author and ship
  (이모 · 고모 · 삼촌 → 친척; 회사 · 직장 · 팀 → 직장동료)
- **the map-by-distinct-value screen** — `"이모" (40행) → [ 친척 ▾ ]`
- **the importer writing raw 관계 text into the free label**

The free label column itself **stays**. It was never primarily an import
overflow bin; it is the couple's own field (`친구 / "대학교 동아리 친구들"`).
No schema change: `guest.group_category` and `guest.group_label` are unchanged.

## What the earlier design got wrong about who uploads

The 08-07 design treated an unmapped 관계 as something only we could resolve,
so it built machinery to resolve it cheaply: ship a dictionary, and ask about
whatever the dictionary misses by distinct value rather than by row. Both
mechanisms are sound. They answer a question that turns out not to be asked.

The assumption underneath was that the couple is a **courier** — parents fill
the file, the couple relays it, and by the time anything is wrong the person
who could fix it is a phone call away. On that reading, holding rows back is
punishment: it makes the couple go back to their mother and ask her to redo a
spreadsheet, which in practice does not happen.

The founder's correction is that the couple is a **reviewer**. They open the
file before uploading it. And they can classify their own 이모 — the seven
categories are deliberately coarse enough (2026-08-06 §1: 가족 is one bucket,
혼주 손님 exists) that no row needs knowledge the couple lacks. The one case
that looked like it might — a parent's guest the couple has never met — lands
in 혼주 손님 without the couple needing to know who the person is.

So the dictionary was solving a delivery problem that does not exist, and it
carried a real cost: a table of Korean kinship terms that we cannot finish and
that grows every time someone writes 시누이 or 사돈. Its initial contents had
been an open question since 2026-08-07 (`#33`) precisely because nobody could
write it down.

## Why this does not break "not sure must never block"

The 2026-08-06 rule stands: an undecided row imports as a separate guest rather
than stalling the file. This decision does not touch it, because the two cases
are different in the one way that matters — **whether anyone can answer.**

- **"모르겠다"** is identity ambiguity: is this 김영수 the 김영수 we already
  have? Nobody can be sure at import time, and merging later is lossless. So
  never block: guess separate, decide later.
- **"형식이 틀렸다"** is a malformed field. Someone *can* answer, and they are
  holding the mouse. Importing 40 rows as 기타 and asking afterwards is not
  tolerance, it is deferring a question to a worse moment — after the rows are
  already in the ledger carrying a category the couple did not choose.

The test that separates them: *is there a person who knows the answer right
now?* For identity, no. For 관계, yes.

Nor is this a stall. The good rows import. What comes back is a short list, not
a rejected file.

## Scope — this generalizes past 관계

관계 is the column that motivated it, but the rule is stated on the row: a row
that does not match the template does not import. An empty 이름 and a 참석 인원
that is not a positive integer are the same case, and carving out 관계 alone
would leave the other two undecided for no reason. **Rejection is per row and
never per file** — one bad row must not hold back the other 199.

## The notice, and its honest limit

The notice next to the upload is worth having and will not do much on its own.
The person who reads it is the couple; the person who fills the 관계 column is
a parent, who never sees our screens and only ever touches an .xlsx that
arrived over KakaoTalk. It sets the couple's expectation that they are the
checkpoint. It does not clean the file.

This is also why the .xlsx dropdown stays (08-07): it is the only thing that
reaches the parent at the moment they are typing, and reducing the number of
bad rows is still worth it even though it cannot eliminate them.

## Consequences

- **`#33` (관계 동의어 표의 초기 내용) closes with this record.** There is no
  table to write.
- **`#21` (임포트 매칭) and `#22` (임포트 검토 화면) shrink.** The distinct-value
  mapping UI is gone; the review screen gains a held-back-rows list. Identity
  conflict resolution on that screen is unaffected.
- **`#19` (양식 내려받기)** keeps the dropdown, and gains the notice next to the
  upload control.
- No schema change, no change to `2026-08-06`'s group categories themselves.
