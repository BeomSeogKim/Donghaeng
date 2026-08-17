package com.donghaeng.wedding

import org.springframework.data.jpa.repository.JpaRepository

internal interface WeddingRepository : JpaRepository<Wedding, Long>
