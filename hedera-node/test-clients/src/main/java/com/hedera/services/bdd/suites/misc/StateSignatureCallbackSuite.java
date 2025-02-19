/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.services.bdd.suites.misc;

import static com.hedera.services.bdd.junit.RepeatableReason.USES_STATE_SIGNATURE_TRANSACTION_CALLBACK;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.hapiStateSignature;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usingStateSignatureTransactionCallback;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.swirlds.platform.components.transaction.system.ScopedSystemTransaction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
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
