// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.output;

import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.queries.QueryUtils;
import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.suites.Utils;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class FileYahcliOutput implements YahcliOutput, Closeable {
    private final PrintWriter out;

    /**
     * Create a FileOutput that overwrites the file.
     */
    public FileYahcliOutput(@NonNull final Path file) throws IOException {
        this(file, /* append = */ false, StandardCharsets.UTF_8);
    }

    /**
     * Full-control constructor.
     */
    public FileYahcliOutput(final Path file, final boolean append, final Charset charset) throws IOException {
        requireNonNull(file);
        requireNonNull(charset);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        final OpenOption[] opts = append
                ? new OpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND}
                : new OpenOption[] {
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
                };
        final var bw = Files.newBufferedWriter(file, charset, opts);
        this.out = new PrintWriter(bw, /* autoFlush = */ true);
    }

    @Override
    public synchronized void warn(@NonNull final String notice) {
        requireNonNull(notice);
        out.println(".!. " + notice);
    }

    @Override
    public synchronized void info(@NonNull final String notice) {
        requireNonNull(notice);
        out.println(".i. " + notice);
    }

    @Override
    public synchronized void printGlobalInfo(@NonNull final ConfigManager config) {
        requireNonNull(config);
        final var payer = config.getDefaultPayer();
        final var msg = String.format(
                "Targeting %s, paying with %d.%d.%d",
                config.getTargetName(), payer.getShardNum(), payer.getRealmNum(), payer.getAccountNum());
        out.println(msg);
    }

    @Override
    public synchronized void appendBeginning(@NonNull final FileID target) {
        requireNonNull(target);
        final var msg = "Appending to the uploaded " + Utils.nameOf(target) + "...";
        out.print(msg);
        out.flush();
    }

    @Override
    public synchronized void appendEnding(@NonNull final ResponseCodeEnum resolvedStatus, final int appendsRemaining) {
        requireNonNull(resolvedStatus);
        if (resolvedStatus == ResponseCodeEnum.SUCCESS) {
            out.println(resolvedStatus + " (" + (appendsRemaining - 1) + " appends left)");
        } else {
            out.println(resolvedStatus);
        }
    }

    @Override
    public synchronized void uploadBeginning(@NonNull final FileID target) {
        requireNonNull(target);
        final var msg = "Uploading the " + Utils.nameOf(target) + "...";
        out.print(msg);
        out.flush();
    }

    @Override
    public synchronized void uploadEnding(@NonNull final ResponseCodeEnum resolvedStatus) {
        requireNonNull(resolvedStatus);
        out.println(resolvedStatus);
    }

    @Override
    public synchronized void downloadBeginning(@NonNull final FileID target) {
        requireNonNull(target);
        final var msg = "Downloading the " + Utils.nameOf(target) + "...";
        out.print(msg);
        out.flush();
    }

    @Override
    public synchronized void downloadEnding(@NonNull final Response response) {
        requireNonNull(response);
        try {
            final var precheck = QueryUtils.reflectForPrecheck(response);
            out.println(precheck.toString());
        } catch (Throwable t) {
            // Mirror StdoutOutput's behavior but keep it in the same file
            t.printStackTrace(out);
        }
    }

    @Override
    public synchronized void close() {
        // Ensure any buffered content is flushed and the writer is closed
        out.flush();
        out.close();
    }
}
