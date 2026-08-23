package com.donghaeng.wedding

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate

/**
 * What the person holding an invite link is shown **before** they accept — public,
 * because `web/` generates a TypeScript type from this shape.
 *
 * 결혼식 이름 · 예식일 · 초대한 사람, which is the founder's own list
 * (notes/2026-08-23-decision-the-wedding-has-a-name.md: "초대 수락 화면에 결혼식 이름,
 * 예식일, 신랑 혹은 신부 이름"). It is the same field the couple types in 설정, seen
 * from the other side.
 *
 * **This is the one thing the API publishes about a wedding to somebody who is not in
 * it, and what makes that safe is the token rather than the session.** The invite is
 * 256 bits of CSPRNG compared against a stored hash in constant time, single use and
 * one day long, and holding it already entitles the holder to take the seat — so
 * naming the wedding it opens tells them strictly less than accepting would. There is
 * no id to guess and no id in this response: **no `weddingId`**, so nothing here can
 * be carried to a wedding-scoped endpoint, and every unusable token answers exactly
 * what an unknown one answers.
 *
 * `2026-08-22-decision-the-invite-links-residuals.md` §3 said the accept screen could
 * not name the wedding it was joining, and named the phishing case that costs — an
 * attacker's link binds a victim permanently, with no un-join. **That sentence is
 * superseded by the founder's ask**, and the direction of the trade is worth stating:
 * seeing whose wedding this is *before* accepting is what makes an irreversible
 * choice an informed one.
 *
 * **[invitedBy] is the seat that is already taken**, not the one being filled — the
 * partner who sent the link, with their 측 so the screen can say 신랑 or 신부. Its
 * `name` is nullable because [WeddingSeatResponse]'s is; in practice a claimed seat
 * always carries one, since both write points demand it.
 */
data class InvitePreviewResponse(
    @param:Schema(description = "결혼식 이름, or null when the couple has not given their wedding one", example = "범석 희주의 가을")
    val weddingName: String?,
    @param:Schema(description = "예식일", example = "2026-10-10")
    val weddingDate: LocalDate,
    @param:Schema(description = "The partner who sent the link — the seat that is already taken")
    val invitedBy: WeddingSeatResponse,
)
