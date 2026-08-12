# Decision — login splits by provider, not by layer (2026-08-12)

Prompted by picking up `#37` and finding it too large to be one stop. The
issue, its comments, and the obligations `#6` and `#82` put on it add up to
roughly ten new concepts. The tempo rule allows one or two.

## The call

**`#37` becomes Google only.** 카카오 · 네이버 move to a new issue, built as a
second stop.

    stop 1  #37   구글 로그인 — 인가 코드 흐름, app_user, 세션 발급, CurrentUser
    stop 2  #89   카카오 · 네이버 — 손으로 쓴 ClientRegistration, 이메일 병합이 실제로 일어난다

## Why the provider axis and not the layer axis

The obvious cut was "session mechanism first, OAuth after". It is the wrong
one, and for the reason the tempo record already gives: **a layer slice has no
Red Gate test worth writing.** Session issuance with nothing that logs in can
only be tested by calling the issuer directly — a test of the implementation,
written by the thing it tests. What makes stop 1 a requirement is that a
browser arrives with no cookie and leaves with a session that `CurrentUser`
resolves.

The provider axis is vertical because each provider is a complete round trip.

**Corrected 2026-08-12, after stop 1 shipped.** This section first said stop 2
"repeats stop 1's shape with different URIs and a different user-info payload".
That is wrong, and it was wrong in the direction that matters — it makes `#89`
sound like a mapper and two constants.

**네이버 is plain OAuth 2.0, not OIDC.** No `openid` scope, no ID token, no
nonce, and therefore nothing for `OidcIdTokenValidator` to check. A Naver login
produces an `OAuth2User`, not an `OidcUser`, so it does not merely bypass stop
1's ID-token validation — it walks into the success handler's `OidcUser` cast and
comes out as a masked 500. Kakao is OIDC *if* `openid` is in the scope, which is
its own trap (`#6`: without it Kakao issues no ID token at all and the validation
is a silent no-op).

So `#89` restructures the success handler and has to answer a question stop 1
never faced: what replaces signature-`iss`-`aud`-`exp` validation when the
provider hands back no signed assertion. That is a new concept, not a repeat.

## Why the email merge stays in stop 1

The tempting move is to defer the verified-email merge to stop 2, since a
merge needs two providers. It would be wrong.

With one provider there is still a **second login by the same person**, and it
must find the existing row. `#82`'s failure is not "two accounts appear" — it
is that the lookup misses, the code takes the account-creation branch, and
`ux_app_user_email` rejects it. A silent account split turns into a **500 on
login**. That happens on the second Google login, not on the first Kakao one,
so it belongs to the stop that ships Google.

What genuinely waits for stop 2 is `email_verified_by` having more than one
possible value, and Kakao's `is_email_verified` being read at all.

## What this does not decide

- **It does not narrow the security posture.** Every requirement `#5` handed
  to `#37` — CSPRNG, SHA-256 storage, constant-time comparison, log masking,
  idle *and* absolute expiry, re-issue on login, the cookie flags and their
  profile placement — lands in stop 1 with Google. They are properties of the
  session, and the session is what stop 1 builds. Only PKCE's hand-written half
  and the two non-standard registrations move.
- **It does not decide the session table's columns.** The baseline schema
  deliberately left `session` out (`V1__baseline_schema.sql` header) because
  its columns follow from a mechanism that was not designed yet. Stop 1 is
  where that mechanism is designed, so stop 1 writes `V2`, and the founder
  applies it by hand like every other DDL statement
  (`2026-08-09-decision-schema-ownership.md`).
- **It does not move the OAuth console work.** Registering the Kakao app is
  the founder's, and 카카오 이메일 동의항목 may require a business-app
  conversion. That lead time now sits on stop 2 alone instead of blocking
  login entirely — which is most of the practical reason for cutting here.
