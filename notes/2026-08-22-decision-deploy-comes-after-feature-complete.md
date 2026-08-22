# Decision — deploy is sequenced after feature-complete, and the domain was never the blocker (2026-08-22)

Founder's call: **the domain waits until the service is largely built.** `#96`
and the five deploy issues around it stay `v1` and are picked up last.

Recorded for two reasons — the sequencing binds what an agent may pick up, and
**the analysis that produced the question was wrong**, which is the more useful
half.

## 1. The correction — the domain gates almost nothing

I had told the founder that `#96` (prod 프론트엔드 origin) blocked all six deploy
issues, and that buying a domain would open the lane. Reading the issues rather
than the label says otherwise:

| Issue | What it actually needs |
|---|---|
| `#56` 배포 잡 — 이미지 태그·푸시 | A **registry choice** and a compose file. No domain, no server. |
| `#54` `sslmode=verify-full` 검증 | A **running environment** whose `DATABASE_URL` can be read. Not a name. |
| `#51` Caddy trusted_proxies | The **compose network's addresses** and a Caddy config — neither exists in any note. Not a name. |
| `#46` Cloudflare Pages | A **Pages project**. `*.pages.dev` serves fine; a custom domain is optional. Its real risk is that the build must check out the **repo root**, because `web/src/index.css` imports `../../design/tokens.css` from outside the package root. |
| `#48` CSP · Referrer-Policy · `VITE_` | Partly the origin (CSP `connect-src`), but its central sentence — *`VITE_` variables are public to every visitor; the frontend receives no secrets* — needs nothing. |
| `#96` prod 프론트엔드 origin | **This is the domain.** |

**So the blocker was never the name.** It is that **no registry, no server and no
Pages project exist yet** — and those are founder actions too, which is why the
sequencing call lands the same way either way. The correction matters because it
changes what "unblocking deploy" would have meant, and a wrong blocker sends the
next agent to buy the wrong thing.

## 2. What the sequencing costs

`2026-08-19-decision-launch-date-and-google-only.md` listed **"Nothing is
deployed"** as one of three findings from the pace review that fixed the date.
Deferring again is a knowing repeat of that, so the cost is stated rather than
discovered:

- **The first deploy this product has ever had will happen near the launch date.**
  Every failure mode of a first deploy — a wrong build root, a missing secret, a
  proxy that rewrites a scheme, a `sslmode` that was never checked — arrives at
  once, with the least time to absorb it.
- **`#54` and `#51` cannot be verified early even in principle.** Both are checks
  against a running environment, and both protect real data: `#54` is guest names
  and contacts crossing the wire in plaintext if it is missed.
- **`#46`'s repo-root gotcha is the cheapest thing here to discover late and the
  most annoying** — the Pages build simply fails, and it fails on configuration
  rather than code.

What makes it acceptable: **nothing about the deploy work gets easier or harder
for having been done earlier**, unlike a schema decision, and the feature work it
displaces is the part that decides whether there is anything worth deploying.

## 3. What binds until it is picked up

- **No agent picks up `#46`, `#48`, `#51`, `#54`, `#56` or `#96`** without the
  founder saying deploy is open. They stay `v1`; `post-v1` would be wrong, because
  deferred-within-v1 is exactly what this is.
- **`#48`'s `VITE_` sentence is not gated** and may be written into a record at any
  time if it becomes convenient — it is a statement about how Vite works, not
  about our infrastructure.

Refs `#96`, `#46`, `#48`, `#51`, `#54`, `#56`,
`2026-08-19-decision-launch-date-and-google-only.md`,
`../../notes/infra-zones.md`
