# Decision — the customer word is 결혼식, and 웨딩 stays ours (2026-08-23)

Settled by the founder after `#216` had already swept the screens. This record
exists because the sweep's only justification was "every approved artboard does
it", and an artboard is not a reason a later reader can check.

## 결혼식 on screen, 웨딩 in the code

Every customer-facing string says **결혼식**: 결혼식 만들기, 결혼식 정보,
결혼식을 만들지 못했습니다, 이미 다른 결혼식에 속해 있습니다. `main` is already
consistent — the value of writing it down is that it stays that way.

**웨딩 remains the code's word.** `Wedding`, `weddingId`, `weddingDate`,
`useWeddings`, and the comments that refer to a screen by name all keep it. So
does this directory.

The reason is that 웨딩 is **vendor vocabulary**. It is what the industry calls
the transaction — 웨딩홀, 웨딩플래너, 웨딩박람회 — and a couple does not use it
for the day itself. Our customer is not buying a 웨딩; they are having a 결혼식.
Using the vendors' word makes the product sound like one more vendor, which is
the register `design/AGENTS.md` already rules out for the visual system and had
never ruled out for the copy.

## This is the second pair, and that makes it a rule

`2026-08-23-decision-the-wedding-has-a-name.md` settled the first one — 원장 is
ours, 하객 명부 is theirs. This is the same shape, so state it once as the
general rule: **the word in the chrome and the word in the code are allowed to
differ, and where they do, the code's word is the precise one and the screen's
word is the customer's.**

Two pairs today:

| code, notes, comments | screen |
|---|---|
| 원장 | 하객 명부 |
| 웨딩 | 결혼식 |

A third pair should be recorded here rather than started silently. The failure
this prevents is the one `#216` nearly shipped: a screen headed 결혼식 만들기
that answers 웨딩을 만들지 못했습니다, which reads as two products talking.
