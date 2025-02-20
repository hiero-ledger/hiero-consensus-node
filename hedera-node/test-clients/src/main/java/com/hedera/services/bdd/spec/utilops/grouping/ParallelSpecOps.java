// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.grouping;

import static java.util.stream.Collectors.joining;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.infrastructure.RegistryNotFound;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

public class ParallelSpecOps extends UtilOp implements GroupedOps<ParallelSpecOps> {
    private static final Logger log = LogManager.getLogger(HapiSpecOperation.class);

    private boolean failOnErrors = false;
    private final SpecOperation[] subs;
    private final Map<String, Throwable> subErrors = new HashMap<>();

    public ParallelSpecOps(@NonNull final SpecOperation... subs) {
        this.subs = Objects.requireNonNull(subs);
    }

    public ParallelSpecOps failOnErrors() {
        failOnErrors = true;
        return this;
    }

    @Override
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        CompletableFuture<Void> future = CompletableFuture.allOf(Stream.of(subs)
                .map(op -> CompletableFuture.runAsync(
                        () -> op.execFor(spec).map(t -> subErrors.put(op.toString(), t)),
                        HapiSpec.getCommonThreadPool()))
                .toArray(CompletableFuture[]::new));
        future.join();

        if (subErrors.size() > 0) {
            final var message = describeSubErrors();
            if (message.length() > 0) {
                log.error("Problem(s) with sub-operation(s): {}", message);
            }
        }

        return failOnErrors;
    }

    @Override
    @SuppressWarnings("java:S5960")
    protected void assertExpectationsGiven(final HapiSpec spec) throws Throwable {
        if (failOnErrors && subErrors.size() > 0) {
            Assertions.fail(describeSubErrors());
        }
    }

    private String describeSubErrors() {
        return subErrors.entrySet().stream()
                .filter(e -> !(e.getValue() instanceof RegistryNotFound))
                .peek(e -> e.getValue().printStackTrace())
                .map(e -> e.getKey() + " :: " + e.getValue().getMessage())
                .collect(joining(", "));
    }

    @Override
    public boolean requiresFinalization(@NonNull final HapiSpec spec) {
        return Stream.of(subs).anyMatch(operation -> {
            if (operation instanceof HapiSpecOperation specOperation) {
                return specOperation.requiresFinalization(spec);
            } else {
                return false;
            }
        });
    }

    @Override
    public void finalizeExecFor(HapiSpec spec) throws Throwable {
        for (final var op : subs) {
            if (op instanceof HapiSpecOperation specOperation) {
                if (specOperation.requiresFinalization(spec)) {
                    specOperation.finalizeExecFor(spec);
                }
            }
        }
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("numSubOps", subs.length);
    }
}
