# Decision — network and attack-surface security (2026-07-30)

> **Partly out of scope for v1 as of 2026-08-06** — see
> [2026-08-06-decision-v1-scope-and-meals.md](2026-08-06-decision-v1-scope-and-meals.md).
> With our own RSVP links deferred, the shared-link and per-guest-link tokens,
> their per-link rate limits, and the enumeration-safety requirement have no
> surface in v1. They come back with the links, so nothing here is retracted.
> Session and invite tokens, hashing, log masking, per-wedding rate limits, the
> aggregation-query whitelist, and the deployment rules all still apply.

Scope, as set by the founder: **security against attack — network
exposure, DDoS, token safety, application attack surface.** Data-privacy
and legal obligations are a separate concern and are listed at the bottom
as deliberately deferred, not dropped.

This is a system-design-stage decision, made before any code exists so the
structure carries the guarantees rather than individual code review.

## Perimeter — the DDoS answer is making the origin unreachable

The API runs on a single VPS (workspace PROD standard), so an L3/L4 flood
that reaches the origin cannot be absorbed by the app. The defense is
therefore structural, not appliance-based.

- The web is already on Cloudflare Pages; the **API domain also sits
  behind the Cloudflare proxy**, so the origin IP is not published and
  L3/L4 volume is absorbed at the edge.
- Decisively: **the VPS firewall accepts only Cloudflare ranges and
  Tailscale.** Without this, learning the origin IP (which leaks more
  easily than people expect) bypasses the edge entirely and the proxy
  becomes decorative.
- TLS Full (strict), origin certificate from Cloudflare Origin CA.
- **SSH port 22 is never open to the internet** — administration goes
  through Tailscale.

Considered and left as a card, not adopted: Cloudflare Tunnel would mean
zero inbound ports at all, but the workspace standard scopes Tunnel to DEV
exposure, and proxy + IP allowlist is sufficient. Revisit only if origin
exposure becomes a real problem.

## Rate limiting — two layers, and not by IP alone

- **Edge**: Cloudflare rules absorb crude floods, with the public RSVP
  POST endpoint as the specific target.
- **App**: meaningful-unit limits — **per link token and per wedding**.

**Do not rate-limit on IP alone.** Korean mobile carrier NAT puts many
guests behind one address, so an IP-based limit would block legitimate
guests exactly when responses surge (right after the invitation goes out).
This is a correctness bug disguised as a security control.

## Tokens

The system has four kinds: couple session, shared RSVP link, per-guest
RSVP link, and spouse invite. They share a baseline but must not share
lifetimes or privileges.

### Baseline for all four

- **≥128 bits of CSPRNG randomness**, base64url. Nothing sequential or
  predictable anywhere.
- **Stored hashed (SHA-256), never in plaintext** — a database leak must
  not hand over working links. A slow hash (bcrypt/argon2) is
  *deliberately not used*: these are high-entropy random values, not
  human-chosen passwords, so there is no dictionary attack to slow down
  and a slow hash would only add per-request cost.
- Constant-time comparison.
- Masked in logs and error messages. `Referrer-Policy: no-referrer`,
  since some tokens necessarily travel in URLs.

### Per-token rules

- **Per-guest RSVP token** — travels by KakaoTalk and can be forwarded, so
  it leaves our control by design. The defense is **minimal privilege**,
  not secrecy: it authorizes exactly one thing, responding as that guest,
  with no read access to the ledger or to other guests. Expires after the
  wedding date.
- **Shared link token** — public by nature, therefore not a secret at all.
  The defense is **rotatability**: if it is abused, the couple regenerates
  it and updates the invitation.
- **Invite token** — the most dangerous one, since it grants full ledger
  access. **Single-use, 72-hour expiry, consumed on use.**
- **Session** — both idle and absolute expiry, and the session identifier
  is **regenerated on successful login** (session fixation).

## Authentication path

Kakao OAuth fails in known places, so these are requirements, not
defaults: `state` parameter for CSRF, PKCE, exact-match allowlist for
`redirect_uri`, and full ID-token validation (signature, `iss`, `aud`,
`exp`). Any one of these missing is an account-takeover path.

Side benefit already banked: **holding no passwords makes the service
immune to credential stuffing and brute force**, which is what Korean
services are actually attacked with most.

## Application attack surface — the three real spots in this design

Rather than a generic checklist, these are the places our own decisions
create risk.

1. **Native aggregation queries.** JPA parameter binding means injection
   risk exists *only* here, because we chose to write aggregation as
   native SQL. No string concatenation; sort/filter column names go
   through a whitelist mapping — column names cannot be bound, which is
   exactly where this goes wrong.
2. **Vendor email parsing.** The only place we handle externally authored
   HTML. When showing the raw text back to the couple it is rendered **as
   text, never as HTML** — reaching for `dangerouslySetInnerHTML` to
   "show the original" would create stored XSS.
3. **Excel import.** Size limit, cell-count limit, and zip-bomb defense
   (xlsx is a zip). Conversely, CSV export escapes cells beginning with
   `=`, `+`, `-`, `@` (formula injection).

Plus: CSRF protection on state-changing requests (cookie-based auth), and
**Spring Boot Actuator is never exposed to the internet**.

## Explicitly not doing in v1

Paid WAF tiers, mTLS, intrusion detection, self-run DDoS scrubbing. All
disproportionate at this scale and unmaintainable solo.

But we do keep the minimum ability to **notice** an attack: alerting on
spikes in 401 / 404 / 429. Being breached without knowing is worse than
being breached.

## Deferred (out of this note's scope, not dropped)

Raised during this discussion, to be decided separately:

- [ ] PII handling: no personal data in logs, encrypted backup dumps,
      auditing CSV exports.
- [ ] Tenant isolation enforced structurally (wedding-scoped repository
      access, cross-tenant tests returning 404 not 403) — arguably the
      highest-value item overall.
- [ ] Column-level encryption of contacts: recommended **against** for
      v1, revisit at 축의금.
- [ ] Korean privacy-law obligations: guest consent notice on the RSVP
      form, privacy policy page, defined retention period, and a deletion
      path for the couple. Cannot be retrofitted onto data already
      collected, so it must be settled before launch.
