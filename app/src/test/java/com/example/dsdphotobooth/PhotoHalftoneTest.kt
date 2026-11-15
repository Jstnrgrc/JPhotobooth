package com.example.dsdphotobooth

import org.junit.Assert.*
import org.junit.Test

class PhotoHalftoneTest {
    private fun gray(value: Int) = 0xff000000.toInt() or (value shl 16) or (value shl 8) or value

    @Test fun darkPhotosKeepPaperVisibleAcrossTheImage() {
        val pixels = IntArray(64*64) { gray(0) }
        PhotoHalftone.apply(pixels,64,64)
        val coverage = pixels.count { it == gray(0) }.toDouble()/pixels.size
        assertTrue("ink coverage: $coverage",coverage in .56..0.67)
        for (y in 0 until 64 step 8) for (x in 0 until 64 step 8) {
            assertTrue((y until y+8).any { row -> (x until x+8).any { col -> pixels[row*64+col] == -1 } })
        }
    }

    @Test fun preservesSingleDotWidthDetailsWithoutSpecklingWhiteBackground() {
        val pixels = IntArray(33*64) { i -> if(i % 33 == 16) gray(0) else -1 }
        PhotoHalftone.apply(pixels,33,64)
        assertTrue((0 until 64).count { pixels[it*33+16] == gray(0) } >= 34)
        assertTrue(pixels.indices.all { it % 33 == 16 || pixels[it] == -1 })
    }

    @Test fun lighterTonesUseFewerDotsAndTransparentPixelsUseNone() {
        val counts = listOf(0,96,192,255).map { level ->
            val pixels = IntArray(32*32) { gray(level) }
            PhotoHalftone.apply(pixels,32,32)
            assertTrue(pixels.all { it == -1 || it == gray(0) })
            pixels.count { it != -1 }
        }
        assertTrue(counts.zipWithNext().all { (a,b) -> a > b })
        assertEquals(0,counts.last())
        val transparent = IntArray(15)
        PhotoHalftone.apply(transparent,5,3)
        assertTrue(transparent.all { it == -1 })
    }
}
