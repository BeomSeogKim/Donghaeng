package com.donghaeng.guest

import org.springframework.data.repository.Repository

/**
 * **Deliberately not a `JpaRepository`**, which is where the ledger differs from
 * `wedding/`: `findById`, `getReferenceById`, `existsById` and `deleteById` are all
 * keyed on the primary key alone, and `#12`, `#14` and `#15` address a guest by a
 * `guestId` the caller chose. The scope gate proves the WEDDING is the caller's and
 * says nothing about whose guest that id is, so an inherited `findById(guestId)`
 * would compile, read and return another wedding's row with the whole suite green.
 *
 * Every method is therefore declared here, and one taking a `guestId` takes the
 * wedding with it.
 */
internal interface GuestRepository : Repository<Guest, Long> {
    fun save(guest: Guest): Guest
}
