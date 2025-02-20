// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import static com.hedera.services.bdd.spec.utilops.UtilStateChange.createEthereumAccountForSpec;
import static com.hedera.services.bdd.spec.utilops.UtilStateChange.isEthereumAccountCreatedForSpec;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.convertHapiCallsToEthereumCalls;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CustomSpecAssert extends UtilOp {
    static final Logger log = LogManager.getLogger(CustomSpecAssert.class);

    public static void allRunFor(final HapiSpec spec, final List<SpecOperation> ops) {
        if (spec.isUsingEthCalls()) {
            if (!isEthereumAccountCreatedForSpec(spec)) {
                ops.addAll(createEthereumAccountForSpec(spec));
            }
            executeEthereumOps(spec, ops);
        } else {
            executeHederaOps(spec, ops);
        }
    }

    private static void executeHederaOps(final HapiSpec spec, final List<SpecOperation> ops) {
        for (final var op : ops) {
            handleExec(spec, op);
        }
    }

    private static void executeEthereumOps(final HapiSpec spec, final List<SpecOperation> ops) {
        final var convertedOps = convertHapiCallsToEthereumCalls(
                ops,
                SECP_256K1_SOURCE_KEY,
                spec.registry().getKey(SECP_256K1_SOURCE_KEY),
                spec.setup().defaultCreateGas(),
                spec);
        for (final var op : convertedOps) {
            handleExec(spec, op);
        }
    }

    public static void handleExec(final HapiSpec spec, final SpecOperation op) {
        final var error = op.execFor(spec);
        if (error.isPresent()) {
            log.error("Operation '{}' :: {}", op, error.get().getMessage());
            throw new IllegalStateException(error.get());
        }
    }

    public static void allRunFor(HapiSpec spec, SpecOperation... ops) {
        allRunFor(spec, List.of(ops));
    }

    @FunctionalInterface
    public interface ThrowingConsumer {
        void assertFor(HapiSpec spec, Logger assertLog) throws Throwable;
    }

    private final ThrowingConsumer custom;

    public CustomSpecAssert(ThrowingConsumer custom) {
        this.custom = custom;
    }

    @Override
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        custom.assertFor(spec, log);
        return false;
    }

    @Override
    public String toString() {
        return "CustomSpecAssert";
    }
}
