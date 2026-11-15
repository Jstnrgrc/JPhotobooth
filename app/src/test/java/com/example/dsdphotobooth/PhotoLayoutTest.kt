package com.example.dsdphotobooth
import org.junit.Assert.*
import org.junit.Test

class PhotoLayoutTest {
    @Test fun cropsCenteredWithoutStretchingPortraitOrLandscapeSources() {
        assertArrayEquals(intArrayOf(150,0,450,300),centerCropBounds(600,300,1f))
        assertArrayEquals(intArrayOf(0,225,300,375),centerCropBounds(300,600,2f))
    }
    @Test fun catalogAndExistingOrdersRenderValidPortraitSlots() {
        assertEquals(setOf(1,2,3),PhotoLayouts.catalog.map { it.poses }.toSet())
        assertEquals(listOf("A","K","L"),PhotoLayouts.catalog.map { it.key })
        assertEquals(3f,PhotoLayouts.forSession("B",3).ratio)
        val obsolete = PhotoLayouts.forSession("F",4)
        for (layout in PhotoLayouts.catalog + obsolete + PhotoLayouts.forSession(null,1)) {
            assertTrue(layout.ratio > 1f)
            assertTrue(layout.slots.all { it.valid() && (!layout.centeredColumn || it.isCentered()) })
            assertTrue(layout.slots.indices.all { i -> (0 until i).none { layout.slots[i].overlaps(layout.slots[it]) } })
        }
    }
    @Test fun retiredWindowStillRendersForExistingSessions() {
        val window = requireNotNull(PhotoLayouts.named("M"))
        assertEquals(requireNotNull(PhotoLayouts.named("L")).ratio,window.ratio)
        assertEquals(4,window.poses)
        val (tl,tr,bl,br) = window.slots
        assertTrue(tl.right < tr.left && bl.right < br.left && tl.bottom < bl.top && tr.bottom < br.top)
        assertEquals(1f,tl.left + tr.right,.001f)
        assertEquals(1f,bl.left + br.right,.001f)
    }
    @Test fun artworkSpaceSnapsToCenterWithoutChangingSizeOrVerticalPlacement() {
        val slot = PhotoSlot(.1f,.2f,.5f,.6f).centered()
        assertEquals(.3f,slot.left,.0001f)
        assertEquals(.7f,slot.right,.0001f)
        assertEquals(.2f,slot.top,.0001f)
        assertEquals(.6f,slot.bottom,.0001f)
    }
}
