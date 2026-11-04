package com.example.muzerion

import androidx.media3.exoplayer.source.ShuffleOrder
import org.junit.Assert.*
import org.junit.Test

class AnchoredShuffleOrderTest {
    private fun traversal(order: ShuffleOrder): List<Int> = buildList {
        var index = order.firstIndex
        while (index != -1) {
            check(size <= order.length) { "Shuffle contains a cycle" }
            add(index)
            index = order.getNextIndex(index)
        }
    }

    @Test fun everySelectedTrackHasAllOtherTracksAhead() {
        for (count in listOf(1, 2, 7, 63, 150)) for (selected in 0 until count) {
            val order = AnchoredShuffleOrder.startingAt(count, selected, selected.toLong())
            val path = traversal(order)
            assertEquals(selected, path.first())
            assertEquals(-1, order.getPreviousIndex(selected))
            assertEquals(count, path.size)
            assertEquals((0 until count).toSet(), path.toSet())
            path.zipWithNext().forEach { (a, b) -> assertEquals(a, order.getPreviousIndex(b)) }
            assertEquals(-1, order.getNextIndex(path.last()))
        }
    }

    @Test fun resettingPlaylistAnchorsNewSelectionAndKeepsCustomPolicy() {
        var order: ShuffleOrder = AnchoredShuffleOrder.startingAt(0, 0)
        for (selected in listOf(40, 62, 0, 20)) {
            order = order.cloneAndSet(63, selected)
            assertEquals(selected, order.firstIndex)
            assertEquals(63, traversal(order).size)
            assertEquals(-1, order.getPreviousIndex(selected))
        }
    }

    @Test fun navigationDoesNotReshuffleAndCanReturnThroughHistory() {
        val order = AnchoredShuffleOrder.startingAt(63, 52, 123L)
        val before = traversal(order)
        repeat(5) { assertEquals(before, traversal(order)) }
        assertEquals(before.asReversed(), buildList<Int> {
            var index = order.lastIndex
            while (index != -1) { add(index); index = order.getPreviousIndex(index) }
        })
    }

    @Test fun restoredOrderRetainsHistoryAndAnchorsNextNewPlaylist() {
        val original = traversal(AnchoredShuffleOrder.startingAt(63, 37, 54L))
        val restored = AnchoredShuffleOrder.restored(original.toIntArray(), 63)!!
        assertEquals(original, traversal(restored))
        assertEquals(original[19], restored.getPreviousIndex(original[20]))
        assertEquals(12, restored.cloneAndSet(63, 12).firstIndex)
    }

    @Test fun invalidSavedOrdersRejected() {
        assertNull(AnchoredShuffleOrder.restored(intArrayOf(0, 0, 1), 3))
        assertNull(AnchoredShuffleOrder.restored(intArrayOf(0, 1, 5), 3))
        assertNull(AnchoredShuffleOrder.restored(intArrayOf(0, 1), 3))
        assertNull(AnchoredShuffleOrder.restored(intArrayOf(-1), 1))
    }

    @Test fun editsAndEmptyQueueKeepValidPermutationAndPolicy() {
        val order = AnchoredShuffleOrder.startingAt(5, 3, 3L)
            .cloneAndInsert(2, 2).cloneAndRemove(0, 1).cloneAndMove(0, 1, 2)
        assertEquals((0 until 6).toSet(), traversal(order).toSet())
        assertEquals(4, order.cloneAndSet(6, 4).firstIndex)
        val empty = order.cloneAndClear()
        assertTrue(traversal(empty).isEmpty())
        assertEquals(-1, empty.firstIndex)
        assertEquals(2, empty.cloneAndSet(3, 2).firstIndex)
    }
}
