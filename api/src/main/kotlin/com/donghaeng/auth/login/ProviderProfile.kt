package com.donghaeng.auth.login

/**
 * What one completed login tells us about a person, reduced to what `app_user`
 * and `oauth_identity` can hold — and carrying **which provider said it**.
 *
 * [provider] is a field rather than a constant because the alternative is a bug
 * that does not throw. With one registration, hardcoding `"GOOGLE"` is correct;
 * at `#89` a Kakao login would write `provider = 'GOOGLE'` next to a Kakao
 * subject, `ux_oauth_identity_provider_subject` would accept it, and the person's
 * next Google login would find no identity for its own subject and either split
 * their account or collide on the merge key. Nothing about that row looks wrong
 * until someone loses a ledger. It is dispatched from the registration id while
 * there is exactly one case to get right.
 *
 * [mergeKey] is the sensitive field: `null` is the ordinary, safe answer, and a
 * non-null value is a claim that the provider checked mailbox control. Each
 * provider decides what may become one — see [GoogleProfile], and note that
 * `ck_app_user_email_verifier_known` will never accept `NAVER`.
 */
internal data class ProviderProfile(
    val provider: String,
    val subject: String,
    val name: String?,
    val mergeKey: String?,
)
