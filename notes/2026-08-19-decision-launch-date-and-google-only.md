# Decision — the launch date is fixed, and v1 launches on Google alone (2026-08-19)

Two things were settled today, and the second follows from the first.

## The date

**Launch is 2026-08-31.** Twelve calendar days from this record, of which the
last twelve produced nine with commits.

The date is fixed, so **scope is the only thing left that moves** — which is
product value 2 arriving as an operational rule rather than a slogan. What
that recut is exactly is not settled here; it is being cut requirement by
requirement against one test: *does this stand between a couple and a working
원장?*

## Why the pace review preceded it

The date was fixed after measuring where the first eleven days went, and the
measurement is the reason the recut is possible rather than merely hopeful:

- **Zero of the twenty-two v1 feature issues (`#5`–`#26`) were closed.** The
  thirty-five closed issues are scaffolding, infrastructure, and hardening.
- **Forty-two issues were discovered after the cut**, and login alone accounts
  for roughly twenty-five of them.
- **Nothing is deployed.**

The finding is not that the work was wrong — every one of those hardening
issues was real. It is that **there was no gate between discovering something
and paying for it**, so log masking, 4xx logging, pre-auth rate limiting and
session retention were all bought before a single guest row existed and before
anything was deployed. Hardening ran ahead of the data it protects.

**The gate now exists: a discovered issue is asked "does this block v1?" and
if not it goes to `post-v1` and the train does not stop.** Not closed, not
argued about.

The second structural loss was serialization — `#39` has been open since
08-08, so `web/` cannot generate its types and has never been able to move
alongside `api/`. `api/` is 10,717 lines and `web/` is 1,187.

## Google alone, and the reason is not code

**`#89` (카카오 · 네이버) moves to `post-v1`.** The mappers are a day's work
and the schema already anticipates all three — `oauth_identity` is per
provider, and `ck_app_user_email_verifier_known` already refuses NAVER as a
verifier because a Naver address is user-editable.

What does not fit is **the external review queue**. Kakao needs a business-app
conversion and business-information review before email can be a consent item;
Naver needs a service review. **Neither is time we can write code to shorten,
and neither has a promised date** — so committing the launch to them is
committing it to someone else's calendar.

Deferred along with it, because they have no trigger without a second
provider: **`#110`** (us becoming the verifier when a provider hands over no
address) and **the remaining half of `#94`**. With one provider nobody arrives
without a verified address, so the account-merge problem that `#110` exists to
solve cannot occur in v1.

The cost is real and is named here rather than discovered later: **Korean
couples reach for 카카오 first, and v1 will not offer it.** That is friction
at the very first screen, accepted knowingly because the alternative is a
launch date owned by a review queue.

Refs `#89`, `#94`, `#110`, `#39`, `#6`,
`2026-08-06-decision-v1-scope-and-meals.md`,
`2026-08-12-decision-login-slice-by-provider.md`
