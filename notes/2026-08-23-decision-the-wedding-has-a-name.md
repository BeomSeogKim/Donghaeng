# Decision — a wedding carries a name the couple writes (2026-08-23)

The founder's canvas note on 웨딩 만들기 was one line: **"Wedding에 이름을 넣을
수 있었으면 함."** A second note on 초대 수락 asked the invited person be shown
결혼식 이름 · 예식일 · (신랑 혹은 신부) 이름 — the same field, seen from the
other side.

## Free text, and that was the choice

The alternative was composing the name from the two seats — "김범석 · 이서연의
결혼". It was rejected on a fact this product already has: **v1 never records the
partner's name until they accept** (`2026-08-22-decision-the-invite-link.md`).
An auto-composed name would be half a name on screen for the whole span between
creating a wedding and the partner arriving — which is exactly the span the
couple uses the ledger most.

So the field is free text the couple writes. It is optional at creation and
editable in 설정.

## What it changed on screen, which is more than a field

"원장" is the word this repo uses among ourselves; it was never a word for a
customer. Once a wedding has a name, **the name is the screen's title and 원장
has nowhere left to stand** — the ledger's heading becomes 범석 희주의 가을 and
the product word in the chrome becomes **하객 명부**.

This settles two of the founder's notes at once: "원장이라는 용어는 우리 개발
내에서만 사용" and "이름이나 이런 부분들에 대해서 좀 고급지게 갔으면 함". The
second was not a request for a bigger font. It was a request for the screen to be
about this couple's wedding rather than about a feature.

`원장` stays in `notes/`, in `AGENTS.md` and in code identifiers, where it is
precise and costs nothing.
