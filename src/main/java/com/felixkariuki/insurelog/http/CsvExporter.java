package com.felixkariuki.insurelog.http;

import com.felixkariuki.insurelog.model.LogEvent;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Writes events as CSV for spreadsheets and audits. */
final class CsvExporter {

    static final String HEADER = "id,timestamp,level,category,eventType,message,source,policyNumber,claimId,"
            + "customerId,product,amount,currency,channel,attributes";

    private CsvExporter() {
    }

    static String toCsv(List<LogEvent> events) {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (LogEvent e : events) {
            sb.append(String.join(",",
                    cell(e.id()), cell(e.timestamp()), cell(e.level()), cell(e.category()), cell(e.eventType()),
                    cell(e.message()), cell(e.source()), cell(e.policyNumber()), cell(e.claimId()),
                    cell(e.customerId()), cell(e.product()), cell(e.amount() == null ? null : e.amount().toPlainString()),
                    cell(e.currency()), cell(e.channel()), cell(attributes(e.attributes()))))
              .append('\n');
        }
        return sb.toString();
    }

    private static String attributes(Map<String, String> attrs) {
        if (attrs == null || attrs.isEmpty()) {
            return null;
        }
        return attrs.entrySet().stream().map(en -> en.getKey() + "=" + en.getValue()).collect(Collectors.joining("; "));
    }

    /** Quotes a value and neutralises spreadsheet formulas (CSV injection). */
    private static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
