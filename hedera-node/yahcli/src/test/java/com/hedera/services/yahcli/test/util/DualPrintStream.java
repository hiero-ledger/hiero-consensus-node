// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;

public class DualPrintStream extends PrintStream {
    private final PrintStream original;

    public DualPrintStream(OutputStream out, PrintStream original) {
        super(out);
        this.original = original;
    }

    @Override
    public void println(String x) {
        super.println(x);
        original.println(x);
    }

    @Override
    public void print(@Nullable String s) {
        super.print(s);
        original.print(s);
    }

    @Override
    public void print(char c) {
        super.print(c);
        original.print(c);
    }

    @Override
    public void println(int x) {
        super.println(x);
        original.println(x);
    }

    @Override
    public void write(int b) {
        super.write(b);
        original.write(b);
    }

    @Override
    public void write(@NonNull byte[] buf) throws IOException {
        super.write(buf);
        original.write(buf);
    }

    @Override
    public void write(@NonNull byte[] buf, int off, int len) {
        super.write(buf, off, len);
        original.write(buf, off, len);
    }

    @Override
    public void writeBytes(@NonNull byte[] buf) {
        super.writeBytes(buf);
        original.writeBytes(buf);
    }
}
