package com.example.muzerion

/** Move one item relative to another without dropping or duplicating any queue entries. */
internal fun moveQueueEntry(order: List<Int>, source: Int, target: Int, after: Boolean): List<Int> {
    if (source == target || source !in order || target !in order) return order
    return order.toMutableList().apply {
        remove(source)
        add(indexOf(target) + if (after) 1 else 0, source)
    }
}
