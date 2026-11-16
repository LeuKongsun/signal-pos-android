package com.example.signalpos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.pdf.PrintedPdfDocument;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Draws the same paper slip shown on screen, as a narrow black-and-white receipt. */
final class ReceiptPrintAdapter extends PrintDocumentAdapter {
    static final class Line {
        static final int CENTER = 0, ITEM = 1, AMOUNT = 2, TOTAL = 3, NOTE = 4, META = 5, DASH = 6, RULE = 7, SPACE = 8;
        final int kind;
        final String left;
        final String right;
        final boolean strong;
        final boolean accent;

        private Line(int kind, String left, String right, boolean strong, boolean accent) {
            this.kind = kind;
            this.left = left == null ? "" : left;
            this.right = right == null ? "" : right;
            this.strong = strong;
            this.accent = accent;
        }

        static Line center(String text, boolean strong, boolean accent) { return new Line(CENTER, text, "", strong, accent); }
        static Line pair(int kind, String left, String right) { return new Line(kind, left, right, kind == TOTAL, false); }
        static Line dash() { return new Line(DASH, "", "", false, false); }
        static Line rule() { return new Line(RULE, "", "", false, false); }
        static Line space() { return new Line(SPACE, "", "", false, false); }
    }

    private static final int SLIP = 250;
    private final Context context;
    private final List<Line> lines;
    private PrintAttributes attributes;
    private int pages = 1;

    ReceiptPrintAdapter(Context context, List<Line> lines) {
        this.context = context;
        this.lines = lines;
    }

    @Override public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                                   CancellationSignal cancellation, LayoutResultCallback callback, Bundle extras) {
        if (cancellation.isCanceled()) { callback.onLayoutCancelled(); return; }
        attributes = newAttributes;
        PrintedPdfDocument document = new PrintedPdfDocument(context, newAttributes);
        try {
            int width = Math.min(SLIP, document.getPageContentRect().width());
            int height = document.getPageContentRect().height();
            pages = Math.max(1, countPages(width, height));
            callback.onLayoutFinished(new PrintDocumentInfo.Builder("Signal receipt.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(pages).build(), true);
        } catch (Exception error) {
            callback.onLayoutFailed("Could not lay out receipt");
        } finally {
            document.close();
        }
    }

    @Override public void onWrite(PageRange[] ranges, ParcelFileDescriptor destination,
                                  CancellationSignal cancellation, WriteResultCallback callback) {
        PrintedPdfDocument document = new PrintedPdfDocument(context, attributes);
        try (FileOutputStream output = new FileOutputStream(destination.getFileDescriptor())) {
            for (int page = 0; page < pages; page++) {
                if (cancellation.isCanceled()) { callback.onWriteCancelled(); return; }
                boolean include = false;
                for (PageRange range : ranges) if (page >= range.getStart() && page <= range.getEnd()) include = true;
                if (!include) continue;
                PdfDocument.Page sheet = document.startPage(page);
                Canvas canvas = sheet.getCanvas();
                int contentLeft = sheet.getInfo().getContentRect().left;
                int contentTop = sheet.getInfo().getContentRect().top;
                int contentWidth = sheet.getInfo().getContentRect().width();
                int contentHeight = sheet.getInfo().getContentRect().height();
                int slip = Math.min(SLIP, contentWidth);
                drawPage(canvas, page, contentLeft + (contentWidth - slip) / 2, contentTop, slip, contentHeight);
                document.finishPage(sheet);
            }
            document.writeTo(output);
            callback.onWriteFinished(ranges);
        } catch (Exception error) {
            callback.onWriteFailed("Unable to write receipt PDF");
        } finally {
            document.close();
        }
    }

    private int countPages(int width, int height) {
        int page = 0, y = 0;
        for (Line line : lines) {
            int block = blockHeight(line, width);
            if (y > 0 && y + block > height) { page++; y = 0; }
            y += block;
        }
        return page + 1;
    }

    private void drawPage(Canvas canvas, int pageIndex, int left, int top, int width, int height) {
        int page = 0, y = 0;
        for (Line line : lines) {
            int block = blockHeight(line, width);
            if (y > 0 && y + block > height) { page++; y = 0; }
            if (page == pageIndex) drawLine(canvas, line, left, top + y, width);
            y += block;
            if (page > pageIndex) return;
        }
    }

    private int blockHeight(Line line, int width) {
        if (line.kind == Line.DASH || line.kind == Line.RULE) return 14;
        if (line.kind == Line.SPACE) return 10;
        Paint paint = paintFor(line, false);
        int rows = wrap(line.left, paint, textWidth(line, paint, width)).size();
        return Math.max(1, rows) * (textHeight(paint) + 3) + 2;
    }

    private void drawLine(Canvas canvas, Line line, int left, int top, int width) {
        if (line.kind == Line.DASH || line.kind == Line.RULE) {
            Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);
            rule.setColor(Color.BLACK);
            rule.setStrokeWidth(line.kind == Line.RULE ? 1.4f : 1f);
            if (line.kind == Line.DASH) rule.setPathEffect(new DashPathEffect(new float[]{3, 3}, 0));
            float y = top + 7;
            canvas.drawLine(left, y, left + width, y, rule);
            return;
        }
        if (line.kind == Line.SPACE) return;
        Paint paint = paintFor(line, false);
        if (line.kind == Line.CENTER) {
            paint.setTextAlign(Paint.Align.CENTER);
            int baseline = top + textHeight(paint);
            for (String row : wrap(line.left, paint, width)) {
                canvas.drawText(row, left + width / 2f, baseline, paint);
                baseline += textHeight(paint) + 3;
            }
            return;
        }
        Paint amount = paintFor(line, true);
        amount.setTextAlign(Paint.Align.RIGHT);
        float leftWidth = textWidth(line, paint, width);
        List<String> rows = wrap(line.left, paint, leftWidth);
        int baseline = top + textHeight(paint);
        for (int i = 0; i < rows.size(); i++) {
            canvas.drawText(rows.get(i), left, baseline, paint);
            if (i == 0 && !line.right.isEmpty()) canvas.drawText(line.right, left + width, baseline, amount);
            baseline += textHeight(paint) + 3;
        }
    }

    private float textWidth(Line line, Paint paint, int width) {
        if (line.kind == Line.CENTER || line.right.isEmpty()) return width;
        Paint amount = paintFor(line, true);
        return Math.max(40, width - amount.measureText(line.right) - 8);
    }

    private Paint paintFor(Line line, boolean amount) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        boolean strong = line.kind == Line.TOTAL || (amount && line.kind == Line.ITEM) || (line.kind == Line.CENTER && line.strong);
        paint.setTypeface(Typeface.create("sans-serif", strong ? Typeface.BOLD : Typeface.NORMAL));
        float size = 11f;
        if (line.kind == Line.CENTER && line.strong && !line.accent) size = "SIGNAL COFFEE".equals(line.left) ? 11f : 16f;
        else if (line.kind == Line.TOTAL) size = 14f;
        else if (line.kind == Line.NOTE || line.kind == Line.META) size = 9f;
        paint.setTextSize(size);
        return paint;
    }

    private int textHeight(Paint paint) {
        Paint.FontMetrics metrics = paint.getFontMetrics();
        return (int) Math.ceil(metrics.descent - metrics.ascent);
    }

    private List<String> wrap(String text, Paint paint, float width) {
        List<String> rows = new ArrayList<>();
        String rest = text == null ? "" : text.trim();
        if (rest.isEmpty()) { rows.add(""); return rows; }
        float limit = Math.max(8, width);
        while (!rest.isEmpty()) {
            int count = paint.breakText(rest, true, limit, null);
            if (count <= 0) count = 1;
            if (count < rest.length()) {
                int space = rest.lastIndexOf(' ', count);
                if (space > count / 2) count = space;
            }
            String chunk = rest.substring(0, Math.min(count, rest.length())).trim();
            if (chunk.isEmpty()) {
                chunk = rest.substring(0, 1);
                count = 1;
            }
            rows.add(chunk);
            rest = rest.substring(Math.min(count, rest.length())).trim();
            if (rows.size() > 8) break;
        }
        return rows;
    }
}
