package com.lavasec.app.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProductModelsTest {
    @Test
    fun `pinned product bounds remain exact`() {
        assertEquals(20, TOP_DOMAINS_LIMIT)
        assertEquals(30, LOCAL_LOG_PAGE_SIZE)
        assertEquals(1_000, EXPORT_PAGE_SIZE)
    }

    @Test
    fun `stable page token preserves the first read cut across load more`() {
        val cut = StableReadCut(maxRowId = 91L, capturedAtMillis = 1_234L)
        val first = PageToken(cut = cut)
        val next = first.after(StableKey(timestampMillis = 900L, id = 72L))

        assertNull(first.beforeExclusive)
        assertEquals(cut, next.cut)
        assertEquals(StableKey(timestampMillis = 900L, id = 72L), next.beforeExclusive)
    }

    @Test
    fun `stable read cuts default repositories to continuation epoch zero`() {
        assertEquals(
            0L,
            StableReadCut(maxRowId = 91L, capturedAtMillis = 1_234L).continuationEpoch,
        )
    }

    @Test
    fun `revisioned values reject an invalid authoritative revision`() {
        assertEquals("value", Revisioned(revision = 7L, value = "value").value)
        assertThrows(IllegalArgumentException::class.java) {
            Revisioned(revision = -1L, value = "value")
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `stable page rejects a next token that changes its frozen cut`() {
        StablePage(
            rows = listOf("row"),
            next = PageToken(StableReadCut(maxRowId = 2L, capturedAtMillis = 100L)),
            cut = StableReadCut(maxRowId = 1L, capturedAtMillis = 100L),
        )
    }

    @Test
    fun `stable page owns an unmodifiable row snapshot and keeps value semantics`() {
        val callerRows = mutableListOf("first")
        val cut = StableReadCut(maxRowId = 1L, capturedAtMillis = 100L)
        val page = StablePage(rows = callerRows, next = null, cut = cut)

        callerRows += "late"

        assertEquals(listOf("first"), page.rows)
        assertThrows(UnsupportedOperationException::class.java) {
            (page.rows as MutableList<String>).add("external")
        }
        assertEquals(StablePage(rows = listOf("first"), next = null, cut = cut), page)
        assertEquals(page, page.copy())
    }
}
