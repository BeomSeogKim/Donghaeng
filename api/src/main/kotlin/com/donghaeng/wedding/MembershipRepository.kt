package com.donghaeng.wedding

import org.springframework.data.jpa.repository.JpaRepository

internal interface MembershipRepository : JpaRepository<Membership, Long>
