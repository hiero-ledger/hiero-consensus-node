package com.hedera.node.app.fees;

import java.io.IOException;
import java.io.Writer;

class CSVWriter {

    private final Writer writer;
    private int fieldCount;

    public CSVWriter(Writer fwriter) {
        this.writer = fwriter;
        this.fieldCount = 0;
    }

    private static String escapeCsv(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            value = value.replace("\"", "\"\"");
            return "\"" + value + "\"";
        }
        return value;
    }

    public void write(String s) throws IOException {
        this.writer.write(s);
    }

    public void field(String value) throws IOException {
        if (this.fieldCount > 0) {
            this.write(",");
        }
        this.write(escapeCsv(value));
        this.fieldCount += 1;
    }

    public void endLine() throws IOException {
        this.write("\n");
        this.fieldCount = 0;
    }

    public void field(int i) throws IOException {
        this.field(i + "");
    }

    public void field(long fee) throws IOException {
        this.field(fee + "");
    }

    public void fieldPercentage(double diff) throws IOException {
        this.field(String.format("%9.2f%%", diff));
    }

    public void close() throws IOException {
        this.writer.flush();
        this.writer.close();
    }
}
