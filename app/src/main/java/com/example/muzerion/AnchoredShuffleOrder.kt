package com.example.muzerion

import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/** The selected item starts a new shuffle run; forward/back navigation keeps that run. */
internal class AnchoredShuffleOrder private constructor(
    private val order: ShuffleOrder
) : ShuffleOrder by order {
    override fun cloneAndSet(insertionCount: Int, startIndex: Int): ShuffleOrder =
        startingAt(insertionCount, startIndex)

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder =
        AnchoredShuffleOrder(order.cloneAndInsert(insertionIndex, insertionCount))

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder =
        AnchoredShuffleOrder(order.cloneAndRemove(indexFrom, indexToExclusive))

    override fun cloneAndMove(indexFrom: Int, indexToExclusive: Int, newIndexFrom: Int): ShuffleOrder =
        AnchoredShuffleOrder(order.cloneAndMove(indexFrom, indexToExclusive, newIndexFrom))

    override fun cloneAndClear(): ShuffleOrder = startingAt(0, 0)

    companion object {
        fun startingAt(count: Int, selected: Int, seed: Long = Random.nextLong()): AnchoredShuffleOrder {
            require(count >= 0)
            if (count == 0) return AnchoredShuffleOrder(ShuffleOrder.DefaultShuffleOrder(0, seed))
            val first = selected.takeIf { it in 0 until count } ?: 0
            val remaining = (0 until count).filter { it != first }.shuffled(Random(seed))
            return AnchoredShuffleOrder(ShuffleOrder.DefaultShuffleOrder((listOf(first) + remaining).toIntArray(), seed))
        }

        fun restored(indices: IntArray, count: Int): AnchoredShuffleOrder? {
            if (indices.size != count || indices.toSet() != (0 until count).toSet()) return null
            return AnchoredShuffleOrder(ShuffleOrder.DefaultShuffleOrder(indices, Random.nextLong()))
        }
    }
}
