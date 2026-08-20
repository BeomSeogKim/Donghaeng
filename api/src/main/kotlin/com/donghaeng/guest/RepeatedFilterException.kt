package com.donghaeng.guest

import com.donghaeng.error.DomainException
import org.springframework.http.HttpStatus

/**
 * A ledger filter sent more than once — `?side=GROOM&side=BRIDE`, which is what
 * "both chips selected" looks like when a client builds its query from filter state.
 *
 * **Refused rather than resolved, because the alternative is a wrong ledger with a
 * 200 on it.** Spring hands a repeated parameter to a scalar target as an array and
 * the converter keeps the first value, so a caller asking for both sides would be
 * shown one — no error, nothing in the response saying so, and no way for the couple
 * to notice. Never-wrong numbers is the first product value, and a silently narrowed
 * list is the worst available outcome (`notes/2026-08-20-decision-the-ledger-read-and-its-filters.md`
 * §5). "Both" is spelled by leaving the filter out.
 *
 * **The `code` is the one an unconvertible value already gets, on purpose.** Both
 * mean "your filter was wrong" to the person looking at the screen, and a second
 * code would buy the frontend a distinction it has no different copy for. It is a
 * [DomainException] only so that the refusal is thrown from where the request shape
 * is read, with a `detail` we wrote rather than one Spring composed out of the
 * submitted value.
 */
internal class RepeatedFilterException(
    filter: String,
) : DomainException(
        code = "BAD_REQUEST",
        status = HttpStatus.BAD_REQUEST,
        // The name is one of this endpoint's own literals, never a caller-supplied
        // string — `detail` must not reflect what was sent (docs/api-spec.md).
        detail = "The `$filter` filter may be sent at most once.",
    )
