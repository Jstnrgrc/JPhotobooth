package com.example.dsdphotobooth
import org.junit.Assert.*
import org.junit.Test

class EscPosRasterTest {
    @Test fun packsBlackPixelsMostSignificantBitFirst() {
        val pixels = IntArray(8) { -1 }.also { it[0] = 0xff000000.toInt(); it[7] = it[0] }
        val image = EscPosRaster.encode(pixels,8,1)
        assertArrayEquals(byteArrayOf(0x1d,0x76,0x30,0,1,0,1,0,0x81.toByte()),image.single())
    }
    @Test fun everyCopyHasMeasuredBlankMarginsBeforeTheCutAndNoResetBetweenCopies() {
        val image = EscPosRaster.encode(IntArray(576*17) { 0xff000000.toInt() },576,17)
        val copies = EscPosRaster.copies(image,576,2,30,ReceiptCut.FEED_AND_CUT)
        assertArrayEquals(byteArrayOf(0x1b,0x61,0),copies[0].first())
        copies.forEachIndexed { i, copy ->
            val firstImage = copy.indexOfFirst { it === image.first() }
            val afterImage = firstImage + image.size
            val head = copy.subList(if (i == 0) 1 else 0,firstImage)
            val tail = copy.subList(afterImage,copy.size-1)
            assertEquals(24,blankRows(head))
            assertEquals(240,blankRows(tail))
            assertArrayEquals(byteArrayOf(0x1d,0x56,65,0),copy.last())
        }
    }

    @Test fun everyCutterModeEndsEachCopyWithItsCutCommand() {
        val image = EscPosRaster.encode(IntArray(576) { 0xff000000.toInt() },576,1)
        val commands = mapOf(
            ReceiptCut.PARTIAL to byteArrayOf(0x1d,0x56,1),
            ReceiptCut.FULL to byteArrayOf(0x1d,0x56,0),
            ReceiptCut.FEED_AND_CUT to byteArrayOf(0x1d,0x56,65,0),
            ReceiptCut.REPOSITION to byteArrayOf(0x1d,0x56,103,0)
        )
        for (mode in ReceiptCut.entries) {
            val copies = EscPosRaster.copies(image,576,5,30,mode)
            assertEquals(5,copies.size)
            copies.forEach { assertArrayEquals(commands.getValue(mode),it.last()) }
        }
    }

    @Test fun trimsOnlyTheOutsideWhitespace() {
        val pixels = IntArray(8 * 5) { -1 }
        pixels[8] = 0xff000000.toInt()
        pixels[24] = 0xff000000.toInt()
        val packet = EscPosRaster.encode(pixels,8,5,trim=true).single()
        assertEquals(3,packet[6].toInt())
        assertArrayEquals(byteArrayOf(0x80.toByte(),0,0x80.toByte()),packet.copyOfRange(8,11))
    }

    @Test fun compactCutsUseTheSameMarginsAcrossJobsAndWithinACopyBatch() {
        val image = EscPosRaster.encode(IntArray(576) { 0xff000000.toInt() },576,1)
        val first = EscPosRaster.copies(image,576,1,30,ReceiptCut.REPOSITION).single().drop(1)
        val next = EscPosRaster.copies(image,576,2,60,ReceiptCut.REPOSITION)[1]
        assertEquals(first.map { it.toList() },next.map { it.toList() })
        val photo = first.indexOfFirst { it === image.first() }
        assertEquals(24,blankRows(first.take(photo)))
        assertEquals(24,blankRows(first.subList(photo+1,first.lastIndex)))
        assertArrayEquals(byteArrayOf(0x1d,0x56,103,0),first.last())
    }

    private fun blankRows(packets: List<ByteArray>): Int = packets.sumOf { packet ->
        assertArrayEquals(byteArrayOf(0x1d,0x76,0x30,0,72,0),packet.copyOfRange(0,6))
        assertTrue(packet.drop(8).all { it == 0.toByte() })
        val rows = packet[6].toInt()
        assertEquals(8 + rows*72,packet.size)
        rows
    }
}
