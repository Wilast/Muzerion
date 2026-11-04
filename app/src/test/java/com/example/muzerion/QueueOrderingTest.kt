package com.example.muzerion

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class QueueOrderingTest {
    @Test fun moveNextKeepsHistoryAndCurrentTrack() {
        assertEquals(listOf(4,2,3,1,0),moveQueueEntry(listOf(4,2,1,0,3),3,2,true))
    }
    @Test fun moveBeforeAndAfterAcrossBothDirections() {
        val order=listOf(0,1,2,3)
        assertEquals(listOf(3,0,1,2),moveQueueEntry(order,3,0,false))
        assertEquals(listOf(1,2,3,0),moveQueueEntry(order,0,3,true))
        assertEquals(listOf(0,2,1,3),moveQueueEntry(order,2,1,false))
        assertEquals(listOf(0,2,1,3),moveQueueEntry(order,1,2,true))
    }
    @Test fun invalidAndSelfMovesLeaveOrderUnchanged() {
        val order=listOf(2,0,1)
        assertEquals(order,moveQueueEntry(order,2,2,true))
        assertEquals(order,moveQueueEntry(order,9,2,true))
        assertEquals(order,moveQueueEntry(order,2,9,false))
        assertTrue(moveQueueEntry(emptyList(),0,1,false).isEmpty())
    }
    @Test fun manyEditsNeverLoseDuplicateOrReorderOtherItems() {
        val random=Random(300)
        repeat(1000) {
            val order=(0 until 63).shuffled(random)
            val source=random.nextInt(63);val target=random.nextInt(63)
            val changed=moveQueueEntry(order,source,target,random.nextBoolean())
            assertEquals(order.toSet(),changed.toSet())
            assertEquals(order.size,changed.size)
            assertEquals(order.filter { it!=source },changed.filter { it!=source })
        }
    }
}
