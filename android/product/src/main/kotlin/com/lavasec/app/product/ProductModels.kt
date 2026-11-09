package com.lavasec.app.product

import java.util.Collections

const val TOP_DOMAINS_LIMIT = 20
const val LOCAL_LOG_PAGE_SIZE = 30
const val EXPORT_PAGE_SIZE = 1_000

data class Revisioned<out T>(
    val revision: Long,
    val value: T,
) {
    init {
        require(revision >= 0L)
    }
}

data class StableReadCut(
    val maxRowId: Long,
    val capturedAtMillis: Long,
    val continuationEpoch: Long = 0L,
) {
    init {
        require(maxRowId >= 0L)
        require(capturedAtMillis >= 0L)
        require(continuationEpoch >= 0L)
    }
}

data class StableKey(
    val timestampMillis: Long,
    val id: Long,
) {
    init {
        require(timestampMillis >= 0L)
        require(id > 0L)
    }
}

data class PageToken(
    val cut: StableReadCut,
    val beforeExclusive: StableKey? = null,
) {
    init {
        require(beforeExclusive == null || beforeExclusive.id <= cut.maxRowId) {
            "page position must belong to the frozen read cut"
        }
    }

    fun after(key: StableKey): PageToken = copy(beforeExclusive = key)
}

class StablePage<T>(
    rows: List<T>,
    val next: PageToken?,
    val cut: StableReadCut,
) {
    val rows: List<T> = immutableListSnapshot(rows)

    init {
        require(next == null || next.cut == cut) {
            "next page must preserve the first-page read cut"
        }
    }

    fun copy(
        rows: List<T> = this.rows,
        next: PageToken? = this.next,
        cut: StableReadCut = this.cut,
    ): StablePage<T> = StablePage(rows = rows, next = next, cut = cut)

    operator fun component1(): List<T> = rows

    operator fun component2(): PageToken? = next

    operator fun component3(): StableReadCut = cut

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is StablePage<*> && rows == other.rows && next == other.next && cut == other.cut)

    override fun hashCode(): Int {
        var result = rows.hashCode()
        result = 31 * result + (next?.hashCode() ?: 0)
        result = 31 * result + cut.hashCode()
        return result
    }

    override fun toString(): String = "StablePage(rows=$rows, next=$next, cut=$cut)"
}

private fun <T> immutableListSnapshot(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
