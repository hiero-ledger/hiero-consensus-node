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

package com.swirlds.platform.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.platform.system.transaction.TransactionWrapper;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TransactionUtilsTest {

    @ParameterizedTest
    @MethodSource("buildArgumentsAppTransactions")
    void testSizeComparisonsAppTransactions(final Bytes transaction, final TransactionWrapper swirldTransaction) {
        assertEquals(TransactionUtils.getLegacyTransactionSize(transaction), swirldTransaction.getSize());
    }

    protected static Stream<Arguments> buildArgumentsAppTransactions() {
        final List<Arguments> arguments = new ArrayList<>();
        final Randotron randotron = Randotron.create();

        IntStream.range(0, 100).forEach(i -> {
            final Bytes payload = randotron.nextHashBytes();

            arguments.add(Arguments.of(payload, new TransactionWrapper(payload)));
        });

        return arguments.stream();
    }

    @ParameterizedTest
    @MethodSource("buildArgumentsStateSignatureTransaction")
    void testSizeComparisonsStateSignatureTransaction(
            final Bytes payload, final TransactionWrapper stateSignatureTransaction) {
        assertEquals(TransactionUtils.getLegacyTransactionSize(payload), stateSignatureTransaction.getSize());
    }

    protected static Stream<Arguments> buildArgumentsStateSignatureTransaction() {
        final List<Arguments> arguments = new ArrayList<>();
        final Randotron randotron = Randotron.create();

        IntStream.range(0, 100).forEach(i -> {
            final StateSignatureTransaction payload = StateSignatureTransaction.newBuilder()
                    .hash(randotron.nextHashBytes())
                    .signature(randotron.nextSignatureBytes())
                    .build();
            final Bytes bytes = StateSignatureTransaction.PROTOBUF.toBytes(payload);

            arguments.add(Arguments.of(bytes, new TransactionWrapper(bytes)));
        });

        return arguments.stream();
    }
}
