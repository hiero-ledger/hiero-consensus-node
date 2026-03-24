// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

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
        writer.append(String.format("\"%s\":\"%s\"", name, value.replaceAll("\n", " ")));
        this.start = false;
    }

    public void startObject(String name) throws IOException {
        if (!this.start) {
            writer.append(", ");
        }
        writer.append(String.format("\"%s\": {", name));
        this.start = true;
    }

    public void endObject() throws IOException {
        writer.append(" }");
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

    public void key(String name, List<Map<String, Object>> value) throws IOException {
        if (!this.start) {
            writer.append(", ");
        }
        writer.append(String.format("\"%s\" : [", name));
        for (int i = 0; i < value.size(); i++) {
            if (i > 0) writer.append(", ");
            writer.append("{ ");
            boolean first = true;
            for (var entry : value.get(i).entrySet()) {
                if (!first) writer.append(", ");
                Object v = entry.getValue();
                if (v instanceof String s) {
                    writer.append(String.format("\"%s\":\"%s\"", entry.getKey(), s.replaceAll("\n", " ")));
                } else {
                    writer.append(String.format("\"%s\":%s", entry.getKey(), v));
                }
                first = false;
            }
            writer.append(" }");
        }
        writer.append("]");
        this.start = false;
    }

    public void close() throws IOException {
        this.writer.flush();
        this.writer.close();
    }
}
