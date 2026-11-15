package com.example.dsdphotobooth

internal data class SaleOrder(val id: Long, val photos: Int, val copies: Int, val unitCents: Long, val paid: Boolean, val layoutKey: String? = null, val extraCopyCents: Long? = null, val createdAt: Long = 0L) {
    val totalCents = OrderPricing.total(unitCents,copies,extraCopyCents)
}

internal object OrderPricing {
    const val MAX_COPIES = 5
    const val MAX_CENTS = 9_999_999L
    private val decimal = Regex("[0-9]{1,5}(?:[.][0-9]{1,2})?")
    fun parse(value: String): Long? {
        val text = value.trim()
        if (!decimal.matches(text)) return null
        val cents = text.toBigDecimal().movePointRight(2).longValueExact()
        return cents.takeIf { it in 1..MAX_CENTS }
    }
    fun total(unitCents: Long, copies: Int, extraCopyCents: Long? = null): Long {
        require(unitCents in 1..MAX_CENTS && copies in 1..MAX_COPIES)
        require(extraCopyCents == null || extraCopyCents in 1..MAX_CENTS)
        return unitCents + (extraCopyCents ?: unitCents) * (copies - 1)
    }
    fun amount(cents: Long) = java.math.BigDecimal.valueOf(cents,2).toPlainString()
    fun peso(cents: Long) = "₱" + amount(cents)
}
