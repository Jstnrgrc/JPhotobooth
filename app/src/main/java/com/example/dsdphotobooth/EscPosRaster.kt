package com.example.dsdphotobooth

internal enum class ReceiptCut(val label: String) {
    PARTIAL("Partial cut"),
    FULL("Full cut"),
    FEED_AND_CUT("Feed and full cut"),
    REPOSITION("Feed, cut & reposition (test support)")
}

internal object EscPosRaster {
    const val DEFAULT_CUT_CLEARANCE_MM = 30
    val cutClearanceRange = 5..60
    // The booth's 203 dpi profile is approximately 8 dots per millimeter.
    private const val DOTS_PER_MM = 8
    private const val BAND_ROWS = 16

    private fun band(width: Int, rows: Int): ByteArray {
        val rowBytes = width / 8
        return ByteArray(8 + rows * rowBytes).also {
            byteArrayOf(0x1d,0x76,0x30,0,rowBytes.toByte(),(rowBytes ushr 8).toByte(),rows.toByte(),0)
                .copyInto(it)
        }
    }

    private fun blankBands(width: Int, millimeters: Int): List<ByteArray> =
        (0 until millimeters * DOTS_PER_MM step BAND_ROWS).map { top ->
            band(width,minOf(BAND_ROWS,millimeters * DOTS_PER_MM-top))
        }

    private val matrix = intArrayOf(0,8,2,10,12,4,14,6,3,11,1,9,15,7,13,5)
    fun encode(pixels: IntArray, width: Int, height: Int, trim: Boolean = false): List<ByteArray> {
        require(width in 8..832 && width % 8 == 0 && height in 1..4000 && pixels.size == width * height)
        val rowBytes = width / 8
        return buildList {
            fun hasInk(row: Int) = (row*width until (row+1)*width).any { (pixels[it] and 0x00ffffff) != 0x00ffffff }
            var first = 0
            var end = height
            if (trim) {
                while (first < end-1 && !hasInk(first)) first++
                while (end > first+1 && !hasInk(end-1)) end--
            }
            for (top in first until end step BAND_ROWS) {
                val rows = minOf(BAND_ROWS,end-top)
                val band = band(width,rows)
                for (y in 0 until rows) for(x in 0 until width) {
                    val rgb = pixels[(top+y)*width+x]
                    val gray = (299*((rgb ushr 16) and 255) + 587*((rgb ushr 8) and 255) + 114*(rgb and 255)) / 1000
                    val threshold = matrix[((top+y) and 3)*4 + (x and 3)]*16 + 8
                    if(gray < threshold) {
                        val i = 8 + y*rowBytes + x/8
                        band[i] = (band[i].toInt() or (128 ushr (x and 7))).toByte()
                    }
                }
                add(band)
            }
        }
    }
    fun copies(image: List<ByteArray>, width: Int, count: Int, cutClearanceMm: Int, cut: ReceiptCut): List<List<ByteArray>> {
        require(image.isNotEmpty() && width in 8..832 && width % 8 == 0 &&
            count in 1..5 && cutClearanceMm in cutClearanceRange)
        val leader = blankBands(width,3)
        val trailer = blankBands(width,if (cut == ReceiptCut.REPOSITION) 3 else cutClearanceMm)
        return List(count) { index ->
            buildList {
                // Leave the printer at its post-cut position between separate orders.
                if (index == 0) add(byteArrayOf(0x1b,0x61,0))
                addAll(leader)
                addAll(image)
                // Rasterize the margin so it advances at the same scale as the photo,
                // independent of the printer's configured text line spacing.
                addAll(trailer)
                when (cut) {
                    ReceiptCut.PARTIAL -> add(byteArrayOf(0x1d,0x56,1))
                    ReceiptCut.FULL -> add(byteArrayOf(0x1d,0x56,0))
                    ReceiptCut.FEED_AND_CUT -> add(byteArrayOf(0x1d,0x56,65,0))
                    ReceiptCut.REPOSITION -> add(byteArrayOf(0x1d,0x56,103,0))
                }
            }
        }
    }
}
