// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.bdd;

import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.prepend;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.infrastructure.SpecStateObserver;
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
public class YahcliCallOperation extends AbstractYahcliOperation<YahcliCallOperation> implements SpecStateObserver {
    private final String[] args;

    @Nullable
    private Consumer<String> outputCb;

    private SpecStateObserver observer;

    private String payer;

    private boolean schedule = false;

    public YahcliCallOperation(@NonNull final String[] args) {
        this.args = requireNonNull(args);
    }

    public YahcliCallOperation exposingOutputTo(@NonNull final Consumer<String> outputCb) {
        this.outputCb = requireNonNull(outputCb);
        return this;
    }

    @Override
    public void observe(@NonNull SpecState specState) {
        observer.observe(specState);
    }

    public YahcliCallOperation observing(@NonNull final SpecStateObserver observer) {
        this.observer = observer;
        return this;
    }

    public YahcliCallOperation payingWith(String payer) {
        this.payer = payer;
        return this;
    }

    public YahcliCallOperation schedule() {
        this.schedule = true;
        return this;
    }

    @Override
    protected YahcliCallOperation self() {
        return this;
    }

    @Override
    public Optional<Throwable> execFor(@NonNull final HapiSpec spec) {
        requireNonNull(spec);
        final var yahcli = new Yahcli();
        if (observer != null) {
            yahcli.setStateObserver(observer);
        }
        final var commandLine = new CommandLine(yahcli);
        var finalizedArgs = args;
        if (!workingDirProvidedViaArgs()) {
            final var w = workingDirOrThrow();
            finalizedArgs = prepend(finalizedArgs, "-w", w);
        }
        if (!configProvidedViaArgs()) {
            final var c = configLocOrThrow();
            finalizedArgs = prepend(finalizedArgs, "-c", c);
        }
        if (schedule) {
            finalizedArgs = prepend(finalizedArgs, "--schedule");
        }
        if (payer != null) {
            finalizedArgs = prepend(finalizedArgs, "-p", payer);
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
            //            if (observer != null) {
            //                observer.observe(new SpecState(spec.registry(), spec.keys()));
            //            }
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
