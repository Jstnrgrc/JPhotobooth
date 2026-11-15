package com.example.dsdphotobooth

import org.junit.Assert.*
import org.junit.Test

class OrderPricingTest {
    @Test fun firstCopyUsesTheLayoutPriceAndAdditionalCopiesUseTheConfiguredPrice() {
        val order = SaleOrder(1,2,3,5000,false,"K",1500)
        assertEquals(8000L,order.totalCents)
        assertEquals(5000L,order.copy(copies=1).totalCents)
        assertEquals(15000L,order.copy(extraCopyCents=null).totalCents)
    }
    @Test fun multipliesExactCentavosWithoutRounding() {
        val unit = requireNotNull(OrderPricing.parse("49.95"))
        assertEquals("₱149.85",OrderPricing.peso(OrderPricing.total(unit,3)))
    }
    @Test fun rejectsMalformedAndNonpositivePrices() {
        listOf("0","-10","1.234","1e2","1,000","999999").forEach { assertNull(OrderPricing.parse(it)) }
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsCopyCountsOverTheLimit() { OrderPricing.total(4995L,6) }
}
