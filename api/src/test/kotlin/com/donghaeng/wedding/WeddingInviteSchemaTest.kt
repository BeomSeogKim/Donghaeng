package com.donghaeng.wedding

import com.donghaeng.BaselineSchemaFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import java.sql.Connection

/**
 * What `wedding_invite` enforces about the partner's link in `V4__wedding_invite.sql`.
 *
 * The endpoint tests say what the API answers; this says what may EXIST — including
 * when the writer is a fixture, a later feature, or psql. For a bearer credential the
 * difference is the whole point: a second live invite minted by a path nobody
 * reviewed is a second live credential, and the couple would never see it.
 *
 * **Uniqueness and the predicate, never just the name.** A `create index` where the
 * founder meant `create unique index` leaves an index with the right name doing
 * nothing, and `ddl-auto: validate` sees nothing about indexes at all (api/AGENTS.md,
 * Schema ownership).
 *
 * See BaselineSchemaFixture for what "enforces" reaches: the file, not prod.
 */
internal class WeddingInviteSchemaTest : BaselineSchemaFixture() {
    @Test
    fun `a second live invite for one seat is not representable`() {
        rolledBack { connection ->
            val seat = connection.waitingSeat()
            val issuer = connection.insertUser()

            connection.execute(inviteInsert(seat, "selector-one", issuer))

            // 재발급 kills the previous token (notes/2026-08-22-decision-the-invite-link.md
            // §1). The application revokes the outgoing row in the same transaction,
            // and this is what makes that a rule rather than an intention: a couple
            // who taps 재발급 three times must never end up holding three live
            // credentials in three places.
            val second = connection.rejects(inviteInsert(seat, "selector-two", issuer))

            assertThat(second.sqlState).isEqualTo(UNIQUE_VIOLATION)
            assertThat((second as PSQLException).serverErrorMessage?.constraint).isEqualTo("ux_wedding_invite_live")
        }
    }

    @Test
    fun `a spent or revoked invite frees the seat's slot, and an expired one does not`() {
        rolledBack { connection ->
            val seat = connection.waitingSeat()
            val user = connection.insertUser()

            connection.execute(inviteInsert(seat, "spent", user))
            connection.execute("update wedding_invite set accepted_at = now(), accepted_by = $user where selector = 'spent'")
            connection.execute(inviteInsert(seat, "revoked", user))
            connection.execute("update wedding_invite set revoked_at = now() where selector = 'revoked'")

            // Both halves of the partial predicate, asserted by behaviour: an index
            // partial on only one of them would pass the test above and then refuse a
            // reissue for the rest of that seat's life.
            connection.execute(inviteInsert(seat, "live", user))

            // An EXPIRED row still occupies the slot, deliberately — reissue revokes
            // whatever it finds, so the statement that needs the slot is the one that
            // frees it, and nothing else may quietly mint a second live token.
            connection.execute(
                "update wedding_invite set issued_at = now() - interval '2 days', expires_at = now() - interval '1 day' where selector = 'live'",
            )
            val whileExpired = connection.rejects(inviteInsert(seat, "after-expiry", user))

            assertThat(whileExpired.sqlState).isEqualTo(UNIQUE_VIOLATION)
            assertThat(connection.selectLong("select count(*) from wedding_invite where seat_id = $seat")).isEqualTo(3)
        }
    }

    @Test
    fun `one selector resolves to at most one invite, spent rows included`() {
        rolledBack { connection ->
            val first = connection.waitingSeat()
            val second = connection.waitingSeat()
            val issuer = connection.insertUser()
            connection.execute(inviteInsert(first, "same-selector", issuer))
            connection.execute("update wedding_invite set revoked_at = now() where selector = 'same-selector'")

            // NOT partial, unlike every unique index in V1 and exactly like
            // ux_user_session_selector: a selector is 128 bits of CSPRNG, so a dead
            // row can only block a live one by colliding — and a collision is the
            // event this index exists to refuse. The accept path looks a token up
            // here, so "at most one" has to be true of the whole table.
            val collision = connection.rejects(inviteInsert(second, "same-selector", issuer))

            assertThat(collision.sqlState).isEqualTo(UNIQUE_VIOLATION)
            assertThat((collision as PSQLException).serverErrorMessage?.constraint).isEqualTo("ux_wedding_invite_selector")
        }
    }

    @Test
    fun `a link cannot expire before it was issued, and cannot be spent by nobody`() {
        rolledBack { connection ->
            val seat = connection.waitingSeat()
            val user = connection.insertUser()

            val backwards =
                connection.rejects(
                    "insert into wedding_invite (seat_id, selector, verifier_hash, issued_by, issued_at, expires_at) " +
                        "values ($seat, 'backwards', '$HASH', $user, now(), now() - interval '1 hour')",
                )
            assertThat((backwards as PSQLException).serverErrorMessage?.constraint).isEqualTo("ck_invite_expiry_after_issue")

            connection.execute(inviteInsert(seat, "half-spent", user))
            // Half of an acceptance is not an acceptance. `accepted_at` alone is a
            // spent token nobody can be shown to have spent — which is the one
            // question an incident about a bearer credential actually asks.
            val halfSpent = connection.rejects("update wedding_invite set accepted_at = now() where selector = 'half-spent'")
            assertThat((halfSpent as PSQLException).serverErrorMessage?.constraint).isEqualTo("ck_invite_accepted_together")
        }
    }

    @Test
    fun `the verifier is stored hashed and the columns are wide enough for what the application mints`() {
        rolledBack { connection ->
            val seat = connection.waitingSeat()
            val user = connection.insertUser()

            // Sizes are load-bearing and NOTHING else checks them: `ddl-auto: validate`
            // compares JDBC type codes only, so a varchar(32) under a `length = 255`
            // mapping passes and then fails at INSERT — here, on the one write that
            // hands a couple their link.
            connection.execute(
                "insert into wedding_invite (seat_id, selector, verifier_hash, issued_by, issued_at, expires_at) " +
                    "values ($seat, '$SELECTOR', '$HASH', $user, now(), now() + interval '1 day')",
            )

            assertThat(connection.selectText("select selector from wedding_invite where seat_id = $seat")).isEqualTo(SELECTOR)
            assertThat(connection.selectText("select verifier_hash from wedding_invite where seat_id = $seat")).hasSize(64)
        }
    }

    @Test
    fun `the indexes say unique, and say it over the columns they are read on`() {
        rolledBack { connection ->
            // Read back rather than inferred from behaviour, for the property
            // behaviour cannot distinguish: an index that is unique over the right
            // column by accident of some other definition.
            assertThat(connection.selectText("select pg_get_indexdef('ux_wedding_invite_selector'::regclass)"))
                .contains("CREATE UNIQUE INDEX")
                .contains("(selector)")

            assertThat(connection.selectText("select pg_get_indexdef('ux_wedding_invite_live'::regclass)"))
                .contains("CREATE UNIQUE INDEX")
                .contains("(seat_id)")
                .contains("accepted_at IS NULL")
                .contains("revoked_at IS NULL")

            // The plain one beside it, and NOT unique: the partial index above answers
            // only "is there a live invite", so a read of a seat's history — retention,
            // an audit, the FK check on a hard delete — has nothing to use without this.
            assertThat(connection.selectText("select pg_get_indexdef('ix_wedding_invite_seat'::regclass)"))
                .contains("CREATE INDEX")
                .doesNotContain("UNIQUE")
                .contains("(seat_id)")
        }
    }

    /** The empty 신부 seat `POST /weddings` creates — the only row an invite ever points at. */
    private fun Connection.waitingSeat(): Long {
        val person = insertUser()
        val wedding = insertWedding(person)
        return insertReturningId(
            "insert into wedding_party (wedding_id, side, created_at, updated_at) values ($wedding, 'BRIDE', now(), now())",
        )
    }

    /** The shape the application inserts: a live token for a waiting seat. */
    private fun inviteInsert(
        seatId: Long,
        selector: String,
        issuedBy: Long,
    ) = "insert into wedding_invite (seat_id, selector, verifier_hash, issued_by, issued_at, expires_at) " +
        "values ($seatId, '$selector', '$HASH', $issuedBy, now(), now() + interval '1 day')"

    private companion object {
        const val UNIQUE_VIOLATION = "23505"

        /** 16 CSPRNG bytes, base64url unpadded — the widest selector the application mints today. */
        const val SELECTOR = "AAAAAAAAAAAAAAAAAAAAAA"

        /** SHA-256, hex: 64 characters, which is exactly what the column allows. */
        const val HASH = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
