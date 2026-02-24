package com.hedera.node.app.fees;

import java.io.FileWriter;
import java.io.IOException;

class JSONFormatter {

    private final FileWriter writer;
    private boolean start;

    public JSONFormatter(FileWriter writer) {
        this.writer = writer;
        this.start = false;
    }

    public void startRecord() throws IOException {
        writer.write("{ ");
        this.start = true;
    }

    public void key(String name, String value) throws IOException {
        if (!this.start) {
            writer.append(", ");
        }
        writer.append(String.format("\"%s\":\"%s\"", name, value.replaceAll("\n"," ")));
        this.start = false;
    }

    public void endRecord() throws IOException {
        writer.write("}\n");
    }

    public void key(String name, long value) throws IOException {
        if (!this.start) {
            writer.append(", ");
        }
        writer.append(String.format("\"%s\" : %s ", name, "" + value));
    }

    public void key(String name, double value) throws IOException {
        if (!this.start) {
            writer.append(", ");
        }
        writer.append(String.format("\"%s\" : %.5f", name, value));
    }

    public void close() throws IOException {
        this.writer.flush();
        this.writer.close();
    }
}
