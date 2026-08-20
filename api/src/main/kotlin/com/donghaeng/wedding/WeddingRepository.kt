package com.donghaeng.wedding

import org.springframework.data.jpa.repository.JpaRepository

/**
 * **`deleted_at is null` is spelled out here even though `@SQLRestriction` on
 * [Wedding] already adds it.** The ambient filter is what made a deleted wedding
 * with a live membership resolvable in the first place — nothing said the walk had
 * to look at `wedding.deleted_at`, so nothing did — and a condition that carries the
 * gate belongs where the query is read, not one file away
 * (notes/2026-08-10-decision-soft-delete.md).
 */
internal interface WeddingRepository : JpaRepository<Wedding, Long> {
    fun existsByIdAndDeletedAtIsNull(id: Long): Boolean

    fun findByIdAndDeletedAtIsNull(id: Long): Wedding?
}
