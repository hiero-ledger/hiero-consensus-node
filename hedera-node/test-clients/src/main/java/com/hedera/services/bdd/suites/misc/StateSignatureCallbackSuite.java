// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.misc;

import static com.hedera.services.bdd.junit.RepeatableReason.USES_STATE_SIGNATURE_TRANSACTION_CALLBACK;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.hapiStateSignature;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usingStateSignatureTransactionCallback;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

public class StateSignatureCallbackSuite {

    @RepeatableHapiTest(USES_STATE_SIGNATURE_TRANSACTION_CALLBACK)
    @DisplayName("regular transaction does not call StateSignatureTransaction callbacks")
    final Stream<DynamicTest> doesNotCallStateSignatureCallback() {
        final var preHandleCallback = new Callback();
        final var handleCallback = new Callback();
        return hapiTest(cryptoCreate("somebody")
                .balance(0L)
                .withSubmissionStrategy(usingStateSignatureTransactionCallback(preHandleCallback, handleCallback))
                .satisfies(
                        () -> preHandleCallback.counter.get() == 0,
                        "Pre-handle StateSignatureTxnCallback was called but should not")
                .satisfies(
                        () -> handleCallback.counter.get() == 0,
                        "Handle StateSignatureTxnCallback was called but should not"));
    }

    @RepeatableHapiTest(USES_STATE_SIGNATURE_TRANSACTION_CALLBACK)
    @DisplayName("StateSignatureTransaction calls StateSignatureTransaction callbacks")
    final Stream<DynamicTest> callsStateSignatureCallback() {
        final var preHandleCallback = new Callback();
        final var handleCallback = new Callback();
        return hapiTest(hapiStateSignature()
                .withSubmissionStrategy(usingStateSignatureTransactionCallback(preHandleCallback, handleCallback))
                .setNode("0.0.4")
                .fireAndForget()
                .satisfies(
                        () -> preHandleCallback.counter.get() == 1,
                        () ->
                                "Pre-handle StateSignatureTxnCallback should have been called once, but was called was called "
                                        + preHandleCallback.counter.get() + " times")
                .satisfies(
                        () -> handleCallback.counter.get() == 1,
                        () ->
                                "Handle StateSignatureTxnCallback should have been called once, but was called was called "
                                        + handleCallback.counter.get() + " times"));
    }

    private static class Callback implements Consumer<ScopedSystemTransaction<StateSignatureTransaction>> {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public void accept(
                ScopedSystemTransaction<StateSignatureTransaction> stateSignatureTransactionScopedSystemTransaction) {
            counter.incrementAndGet();
        }
    }
}
