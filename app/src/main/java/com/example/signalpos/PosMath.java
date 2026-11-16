package com.example.signalpos;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Amounts are integer hundredths of the named currency, matching the web API. */
public final class PosMath {
    private PosMath() { }
    public static long cents(String input) {
        return cents(input, "USD");
    }
    public static long cents(String input, String currency) {
        if ("KHR".equals(currency) && !input.trim().matches("\\d{1,8}"))
            throw new IllegalArgumentException("Enter a whole riel amount");
        if (!"KHR".equals(currency) && !input.trim().matches("\\d{1,7}(\\.\\d{1,2})?"))
            throw new IllegalArgumentException("Enter an amount with up to two decimal places");
        return new BigDecimal(input.trim()).movePointRight(2).longValueExact();
    }
    public static long discount(List<Long> unitPrices, String type, int percentage) {
        return discount(unitPrices, type, percentage, "USD");
    }
    public static long discount(List<Long> unitPrices, String type, int percentage, String currency) {
        long subtotal = 0;
        for (long price : unitPrices) subtotal += price;
        if ("percentage".equals(type)) return round(subtotal * (percentage / 100.0), currency);
        if (!"buy-two-get-one".equals(type)) return 0;
        List<Long> sorted = new ArrayList<>(unitPrices);
        Collections.sort(sorted);
        long discount = 0;
        for (int i = 0; i < sorted.size() / 3; i++) discount += sorted.get(i);
        return discount;
    }
    public static long includedTax(long total, int basisPoints) {
        return includedTax(total, basisPoints, "USD");
    }
    public static long includedTax(long total, int basisPoints, String currency) {
        if (basisPoints < 0) throw new IllegalArgumentException("Invalid tax rate");
        return round(total - total / (1 + basisPoints / 10000.0), currency);
    }
    private static long round(double amount, String currency) {
        int unit = "KHR".equals(currency) ? 100 : 1;
        return Math.round(amount / unit) * unit;
    }
    public static String amount(long cents, String currency) {
        return String.format(Locale.US, "KHR".equals(currency) ? "%.0f" : "%.2f", cents / 100.0);
    }
    public static String money(long cents) { return money(cents, "USD"); }
    public static String money(long cents, String currency) {
        if (!"KHR".equals(currency)) return "$" + amount(cents, "USD");
        NumberFormat format = NumberFormat.getIntegerInstance(Locale.US);
        return "KHR " + format.format(cents / 100.0);
    }
}
