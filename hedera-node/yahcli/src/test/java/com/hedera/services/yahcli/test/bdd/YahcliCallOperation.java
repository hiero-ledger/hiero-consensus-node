// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.bdd;

import static com.hedera.services.yahcli.test.YahcliVerbs.prepend;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.yahcli.Yahcli;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Assertions;
import picocli.CommandLine;

/**
 * Executes a Yahcli command with the provided arguments against
 * the {@link SubProcessNetwork} targeted by the containing spec.
 */
public class YahcliCallOperation extends AbstractYahcliOperation<YahcliCallOperation> {
    private final String[] args;

    @Nullable
    private Consumer<String> outputCb;

    public YahcliCallOperation(@NonNull final String[] args) {
        this.args = requireNonNull(args);
    }

    public YahcliCallOperation exposingOutputTo(@NonNull final Consumer<String> outputCb) {
        this.outputCb = requireNonNull(outputCb);
        return this;
    }

    @Override
    protected YahcliCallOperation self() {
        return this;
    }

    @Override
    public Optional<Throwable> execFor(@NonNull final HapiSpec spec) {
        requireNonNull(spec);
        final var commandLine = new CommandLine(new Yahcli());
        var finalizedArgs = args;
        if (!workingDirProvidedViaArgs()) {
            final var w = workingDirOrThrow();
            finalizedArgs = prepend(finalizedArgs, "-w", w);
        }
        if (!configProvidedViaArgs()) {
            final var c = configLocOrThrow();
            finalizedArgs = prepend(finalizedArgs, "-c", c);
        }
        try {
            Path outputPath = null;
            if (outputCb != null) {
                outputPath = Files.createTempFile(TxnUtils.randomUppercase(8), ".out");
                finalizedArgs =
                        prepend(finalizedArgs, "-o", outputPath.toAbsolutePath().toString());
            }
            final int rc = commandLine.execute(finalizedArgs);
            if (rc != 0) {
                Assertions.fail(
                        "Yahcli command <<" + String.join(" ", finalizedArgs) + ">> failed with exit code " + rc);
            }
            if (outputPath != null) {
                final var output = Files.readString(outputPath);
                outputCb.accept(output);
                Files.deleteIfExists(outputPath);
            }
        } catch (Throwable t) {
            return Optional.of(t);
        }
        return Optional.empty();
    }

    private boolean workingDirProvidedViaArgs() {
        return argsInclude("-w") || argsInclude("--working-dir");
    }

    private boolean configProvidedViaArgs() {
        return argsInclude("-c") || argsInclude("--config");
    }

    private boolean argsInclude(String s) {
        for (final var arg : args) {
            if (arg.equals(s)) {
                return true;
            }
        }
        return false;
    }
}
