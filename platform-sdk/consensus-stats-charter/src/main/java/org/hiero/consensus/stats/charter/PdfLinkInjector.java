// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.stats.charter;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Post-processes PDF bytes produced by JFreePDF to inject internal link annotations.
 * JFreePDF does not natively support PDF annotations, so this class parses the raw
 * PDF structure, adds annotation dictionary objects, and rewrites the cross-reference table.
 */
final class PdfLinkInjector {

    /** Describes a clickable link rectangle on an index page pointing to a target page. */
    record Link(
            int indexPageObjNum,
            float x1,
            float y1,
            float x2,
            float y2,
            @NonNull String targetPageRef,
            float pageHeight) {}

    private PdfLinkInjector() {}

    /**
     * Injects link annotations into the given PDF bytes.
     *
     * @param original the raw PDF bytes from JFreePDF
     * @param links    list of link annotations to inject
     * @return modified PDF bytes with clickable links
     */
    @NonNull
    static byte[] inject(@NonNull final byte[] original, @NonNull final List<Link> links) {
        if (links.isEmpty()) {
            return original;
        }

        // ISO-8859-1 provides a lossless byte-char round-trip for all 256 byte values
        final String pdf = new String(original, StandardCharsets.ISO_8859_1);

        // 1. Parse the cross-reference table to find object byte offsets
        final int xrefPos = findXrefPosition(pdf);
        final Map<Integer, Integer> objOffsets = parseXref(pdf, xrefPos);
        final String rootRef = extractTrailerValue(pdf, xrefPos, "/Root");
        final String infoRef = extractTrailerValue(pdf, xrefPos, "/Info");

        // 2. Extract each object's raw text using xref offsets
        final Map<Integer, String> objects = extractObjects(pdf, objOffsets, xrefPos);

        // 3. Group links by index page
        final Map<Integer, List<Link>> byPage = new HashMap<>();
        for (final Link link : links) {
            byPage.computeIfAbsent(link.indexPageObjNum(), k -> new ArrayList<>())
                    .add(link);
        }

        // 4. Create annotation objects and modify index page dictionaries
        int nextObjNum = Collections.max(objects.keySet()) + 1;
        for (final Map.Entry<Integer, List<Link>> entry : byPage.entrySet()) {
            final int pageObjNum = entry.getKey();
            final List<Link> pageLinks = entry.getValue();

            final List<String> annotRefs = new ArrayList<>();
            for (final Link link : pageLinks) {
                // Convert Java2D coordinates (top-left origin) to PDF (bottom-left origin)
                final float pdfY1 = link.pageHeight() - link.y2();
                final float pdfY2 = link.pageHeight() - link.y1();
                final String annotObj = nextObjNum + " 0 obj\n"
                        + "<< /Type /Annot /Subtype /Link "
                        + "/Rect [" + fmt(link.x1()) + " " + fmt(pdfY1) + " " + fmt(link.x2()) + " " + fmt(pdfY2)
                        + "] "
                        + "/Border [0 0 0] "
                        + "/Dest [" + link.targetPageRef() + " /Fit] "
                        + ">>\nendobj\n";
                objects.put(nextObjNum, annotObj);
                annotRefs.add(nextObjNum + " 0 R");
                nextObjNum++;
            }

            // Inject /Annots array into the page dictionary (before final ">>")
            final String origPage = objects.get(pageObjNum);
            if (origPage != null) {
                final String annotsEntry = "/Annots [" + String.join(" ", annotRefs) + "]\n";
                final int lastClose = origPage.lastIndexOf(">>");
                final String newPage =
                        origPage.substring(0, lastClose) + annotsEntry + origPage.substring(lastClose);
                objects.put(pageObjNum, newPage);
            }
        }

        // 5. Rebuild the PDF with updated cross-reference table
        return rebuild(pdf, objects, rootRef, infoRef);
    }

    @NonNull
    private static String fmt(final float v) {
        // Format float without unnecessary trailing zeros
        if (v == (int) v) {
            return String.valueOf((int) v);
        }
        return String.format("%.1f", v);
    }

    private static int findXrefPosition(@NonNull final String pdf) {
        final int startxrefIdx = pdf.lastIndexOf("startxref");
        if (startxrefIdx < 0) {
            throw new IllegalArgumentException("Cannot find startxref in PDF");
        }
        // The xref position is on the line after "startxref"
        final int lineStart = pdf.indexOf('\n', startxrefIdx) + 1;
        final int lineEnd = pdf.indexOf('\n', lineStart);
        final int xrefOffset = Integer.parseInt(pdf.substring(lineStart, lineEnd).trim());
        // Verify xref keyword is at that offset
        if (!pdf.startsWith("xref", xrefOffset)) {
            throw new IllegalArgumentException("xref not found at offset " + xrefOffset);
        }
        return xrefOffset;
    }

    @NonNull
    private static Map<Integer, Integer> parseXref(@NonNull final String pdf, final int xrefPos) {
        final Map<Integer, Integer> offsets = new TreeMap<>();
        int pos = pdf.indexOf('\n', xrefPos) + 1; // skip "xref\n"

        while (pos < pdf.length()) {
            final String line = readLine(pdf, pos);
            if (line.startsWith("trailer")) {
                break;
            }

            // Section header: "startObj count"
            final String[] parts = line.trim().split("\\s+");
            if (parts.length == 2) {
                final int startObj = Integer.parseInt(parts[0]);
                final int count = Integer.parseInt(parts[1]);
                pos = pdf.indexOf('\n', pos) + 1;

                for (int i = 0; i < count; i++) {
                    final String entry = readLine(pdf, pos);
                    pos = pdf.indexOf('\n', pos) + 1;
                    // Format: "0000000010 00000 n " or "0000000000 65535 f "
                    final String trimmed = entry.trim();
                    if (trimmed.length() >= 18 && trimmed.charAt(trimmed.length() - 1) == 'n') {
                        final int offset = Integer.parseInt(trimmed.substring(0, 10));
                        offsets.put(startObj + i, offset);
                    }
                }
            } else {
                pos = pdf.indexOf('\n', pos) + 1;
            }
        }
        return offsets;
    }

    @Nullable
    private static String extractTrailerValue(
            @NonNull final String pdf, final int xrefPos, @NonNull final String key) {
        final int trailerStart = pdf.indexOf("trailer", xrefPos);
        if (trailerStart < 0) {
            return null;
        }
        final int startxref = pdf.indexOf("startxref", trailerStart);
        final String trailerSection = pdf.substring(trailerStart, startxref);
        final int keyIdx = trailerSection.indexOf(key);
        if (keyIdx < 0) {
            return null;
        }
        // Value follows the key, e.g., "/Root 1 0 R"
        int valueStart = keyIdx + key.length();
        while (valueStart < trailerSection.length() && trailerSection.charAt(valueStart) == ' ') {
            valueStart++;
        }
        // Read until next "/" or ">>"
        int valueEnd = valueStart;
        while (valueEnd < trailerSection.length()) {
            final char c = trailerSection.charAt(valueEnd);
            if (c == '/' || c == '>') {
                break;
            }
            valueEnd++;
        }
        return trailerSection.substring(valueStart, valueEnd).trim();
    }

    @NonNull
    private static Map<Integer, String> extractObjects(
            @NonNull final String pdf, @NonNull final Map<Integer, Integer> offsets, final int xrefPos) {
        // Sort objects by their byte offset to determine boundaries
        final List<Map.Entry<Integer, Integer>> sorted = new ArrayList<>(offsets.entrySet());
        sorted.sort(Map.Entry.comparingByValue());

        final Map<Integer, String> objects = new TreeMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            final int objNum = sorted.get(i).getKey();
            final int start = sorted.get(i).getValue();
            final int end = (i + 1 < sorted.size()) ? sorted.get(i + 1).getValue() : xrefPos;
            objects.put(objNum, pdf.substring(start, end));
        }
        return objects;
    }

    @NonNull
    private static byte[] rebuild(
            @NonNull final String originalPdf,
            @NonNull final Map<Integer, String> objects,
            @Nullable final String rootRef,
            @Nullable final String infoRef) {
        // Extract header line (%PDF-1.4)
        final String header = originalPdf.substring(0, originalPdf.indexOf('\n') + 1);

        final StringBuilder out = new StringBuilder();
        out.append(header);

        // Write all objects, recording new byte offsets
        final int totalObjects = Collections.max(objects.keySet()) + 1;
        final int[] newOffsets = new int[totalObjects];

        for (final Map.Entry<Integer, String> entry : objects.entrySet()) {
            final int objNum = entry.getKey();
            newOffsets[objNum] = out.length();
            out.append(entry.getValue());
        }

        // Write xref table
        final int xrefOffset = out.length();
        out.append("xref\n");
        out.append("0 ").append(totalObjects).append('\n');
        for (int i = 0; i < totalObjects; i++) {
            if (i == 0) {
                out.append("0000000000 65535 f \n");
            } else if (objects.containsKey(i)) {
                out.append(String.format("%010d 00000 n \n", newOffsets[i]));
            } else {
                out.append("0000000000 65535 f \n");
            }
        }

        // Write trailer
        out.append("trailer\n<< /Size ").append(totalObjects);
        if (rootRef != null) {
            out.append(" /Root ").append(rootRef);
        }
        if (infoRef != null) {
            out.append(" /Info ").append(infoRef);
        }
        out.append(" >>\nstartxref\n").append(xrefOffset).append("\n%%EOF\n");

        return out.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    @NonNull
    private static String readLine(@NonNull final String pdf, final int pos) {
        final int end = pdf.indexOf('\n', pos);
        if (end < 0) {
            return pdf.substring(pos);
        }
        return pdf.substring(pos, end);
    }
}
