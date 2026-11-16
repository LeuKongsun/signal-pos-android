package com.example.signalpos;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;

public class PosMathTest {
    @Test public void moneyUsesExactCents() {
        assertEquals(429, PosMath.cents("4.29"));
        assertEquals(10, PosMath.cents("0.1"));
        assertEquals("$4.29", PosMath.money(429));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsFractionalCents() { PosMath.cents("1.001"); }
    @Test(expected = IllegalArgumentException.class) public void rejectsNegativeCash() { PosMath.cents("-1"); }
    @Test public void percentageRoundsLikeWeb() {
        assertEquals(11, PosMath.discount(Arrays.asList(105L), "percentage", 10));
    }
    @Test public void multiBuyUsesCheapestUnitsAcrossWholeOrder() {
        assertEquals(300, PosMath.discount(Arrays.asList(600L, 400L, 100L, 200L, 300L, 500L), "buy-two-get-one", 0));
        assertEquals(0, PosMath.discount(Arrays.asList(400L, 100L), "buy-two-get-one", 0));
    }
    @Test public void taxIsIncludedRatherThanAddedToTotal() {
        assertEquals(100, PosMath.includedTax(1100,1000));
        assertEquals(0, PosMath.includedTax(1100,0));
    }

    @Test public void khrUsesWholeRielForCashDiscountAndTax() {
        assertEquals(1250000, PosMath.cents("12500", "KHR"));
        assertEquals(1600, PosMath.discount(Arrays.asList(10500L), "percentage", 15, "KHR"));
        assertEquals(1000, PosMath.includedTax(11500, 1000, "KHR"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void khrRejectsFractionalRiel() {
        PosMath.cents("12.50", "KHR");
    }
}
