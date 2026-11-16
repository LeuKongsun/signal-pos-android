package com.example.signalpos;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;

final class ReportCsv {
    private ReportCsv() { }

    static String format(JSONObject report) throws JSONException {
        StringBuilder csv = new StringBuilder("\uFEFF");
        row(csv, "Start", report.getString("start"), "End", report.getString("end"),
                "Timezone", report.getString("timezone"), "Currency", report.getString("currency"));
        row(csv, "Period", "Collected (VAT included)", "Returned", "Net collected", "Orders");
        JSONArray buckets = report.getJSONArray("buckets");
        for (int i = 0; i < buckets.length(); i++) {
            JSONObject bucket = buckets.getJSONObject(i);
            long collected = bucket.getLong("collected"), returned = bucket.getLong("returned");
            row(csv, bucket.getString("label"), amount(collected), amount(returned),
                    amount(collected - returned), bucket.getLong("orders"));
        }
        row(csv);
        row(csv, "Payment", "Collected", "Returned", "Net collected");
        JSONArray payments = report.getJSONArray("payments");
        for (int i = 0; i < payments.length(); i++) {
            JSONObject payment = payments.getJSONObject(i);
            long collected = payment.getLong("collected"), returned = payment.getLong("returned");
            row(csv, payment.getString("method"), amount(collected), amount(returned), amount(collected - returned));
        }
        row(csv);
        row(csv, "Product (before order discounts; current return status)", "Quantity", "Value");
        JSONArray products = report.getJSONArray("products");
        for (int i = 0; i < products.length(); i++) {
            JSONObject product = products.getJSONObject(i);
            row(csv, product.getString("name"), product.getLong("quantity"), amount(product.getLong("amount")));
        }
        return csv.toString();
    }

    private static BigDecimal amount(long cents) {
        return BigDecimal.valueOf(cents, 2).stripTrailingZeros();
    }

    private static void row(StringBuilder csv, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) csv.append(',');
            String value = values[i] instanceof BigDecimal
                    ? ((BigDecimal) values[i]).toPlainString() : values[i].toString();
            if (values[i] instanceof String && !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0)
                value = "'" + value;
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }
}
