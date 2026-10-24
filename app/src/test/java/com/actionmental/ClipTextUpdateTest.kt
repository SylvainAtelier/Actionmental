package com.actionmental

import com.actionmental.data.updateExistingClipText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ClipTextUpdateTest {
    @Test
    fun missingTargetDoesNotDeleteTheDuplicateOrWriteText() {
        val rows = mutableMapOf(2L to "existing content")
        var wrote = false

        val failure = assertThrows(NoSuchElementException::class.java) {
            updateExistingClipText(
                id = 1L,
                exists = { 1L in rows },
                mergeDuplicate = { rows.remove(2L) },
                writeText = { wrote = true; 0 },
            )
        }

        assertEquals("Clipboard entry 1 no longer exists", failure.message)
        assertEquals(mapOf(2L to "existing content"), rows)
        assertFalse(wrote)
    }

    @Test
    fun invalidIdsFailBeforeReadingOrMutatingRows() {
        for (id in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertThrows(NoSuchElementException::class.java) {
                updateExistingClipText(
                    id = id,
                    exists = { error("Invalid IDs must not query rows") },
                    mergeDuplicate = { error("Invalid IDs must not merge rows") },
                    writeText = { error("Invalid IDs must not write rows") },
                )
            }
        }
    }

    @Test
    fun successfulWriteChecksTargetBeforeMergingAndWriting() {
        val calls = mutableListOf<String>()

        val result = updateExistingClipText(
            id = 1L,
            exists = { calls += "exists"; true },
            mergeDuplicate = { calls += "merge" },
            writeText = { calls += "write"; 1 },
        )

        assertEquals(Unit, result)
        assertEquals(listOf("exists", "merge", "write"), calls)
    }

    @Test
    fun zeroUpdatedRowsCannotBeReportedAsSuccess() {
        val failure = assertThrows(NoSuchElementException::class.java) {
            updateExistingClipText(9L, exists = { true }, mergeDuplicate = {}, writeText = { 0 })
        }

        assertEquals("Clipboard entry 9 no longer exists", failure.message)
    }

    @Test
    fun readFailurePropagatesWithoutMutation() {
        val failure = IllegalStateException("Read failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            updateExistingClipText(
                id = 1L,
                exists = { throw failure },
                mergeDuplicate = { error("Failed reads must not merge rows") },
                writeText = { error("Failed reads must not write rows") },
            )
        }

        assertSame(failure, thrown)
    }

    @Test
    fun mergeFailurePropagatesWithoutWritingText() {
        val failure = IllegalStateException("Merge failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            updateExistingClipText(
                id = 1L,
                exists = { true },
                mergeDuplicate = { throw failure },
                writeText = { error("Failed merges must not write text") },
            )
        }

        assertSame(failure, thrown)
    }

    @Test
    fun writeFailurePropagatesToTheTransactionOwner() {
        val failure = IllegalStateException("Write failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            updateExistingClipText(
                id = 1L,
                exists = { true },
                mergeDuplicate = {},
                writeText = { throw failure },
            )
        }

        assertSame(failure, thrown)
    }
}
