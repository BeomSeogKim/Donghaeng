package com.donghaeng.guest

import com.donghaeng.ApiFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * What a ledger test needs beyond [ApiFixture]: an empty `guest` table at both ends,
 * and a [JdbcTemplate] to assert from — the ledger is the first domain whose
 * response deliberately does not publish everything the row holds.
 *
 * **The cleanup is FK-ordered and it is not tidiness.** The Postgres container is
 * shared by every test class and `guest` references `wedding`, which references
 * `app_user`, so a guest left behind here fails the wedding tests' cleanup and the
 * login tests' `users.deleteAll()` — in whichever class happens to run next. SQL
 * rather than `deleteAll()`, which cannot see a soft-deleted row.
 *
 * It is a guest-domain fixture rather than three more lines in [ApiFixture] because
 * it says something about ONE domain's tables; a wedding test deleting from `guest`
 * would be a test cleaning up after a table it never writes.
 */
internal abstract class GuestFixture : ApiFixture() {
    @Autowired protected lateinit var jdbc: JdbcTemplate

    @BeforeEach
    @AfterEach
    fun cleanLedger() {
        jdbc.update("delete from guest")
        jdbc.update("delete from wedding_subscription")
        jdbc.update("delete from wedding_party")
        jdbc.update("delete from wedding")
    }
}
