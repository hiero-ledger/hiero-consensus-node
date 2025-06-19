// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.hevm;

import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion.*;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.wellKnownContextWith;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.wellKnownHapiCall;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.service.contract.impl.exec.ActionSidecarContentTracer;
import com.hedera.node.app.service.contract.impl.exec.TransactionProcessor;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.TinybarValues;
import com.hedera.node.app.service.contract.impl.hevm.*;
import com.swirlds.config.api.Configuration;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HederaEvmTransactionProcessorTest {
    @Mock
    private HederaEvmBlocks blocks;

    @Mock
    private TinybarValues tinybarValues;

    @Mock
    private SystemContractGasCalculator systemContractGasCalculator;

    @Mock
    private HederaWorldUpdater worldUpdater;

    @Mock
    private Supplier<HederaWorldUpdater> feesOnlyUpdater;

    @Mock
    private ActionSidecarContentTracer tracer;

    @Mock
    private Configuration config;

    @Mock
    private TransactionProcessor v30processor;

    @Mock
    private TransactionProcessor v34processor;

    @Mock
    private TransactionProcessor v38processor;

    private HederaEvmTransactionProcessor subject;

    @BeforeEach
    void setUp() {
        subject = new HederaEvmTransactionProcessor(Map.of(
                VERSION_030, v30processor,
                VERSION_034, v34processor,
                VERSION_038, v38processor));
    }

    @Test
    void calls030AsExpected() {
        final var transaction = wellKnownHapiCall();
        final var context = wellKnownContextWith(blocks, false, tinybarValues, systemContractGasCalculator);

        subject.process(transaction, worldUpdater, context, VERSION_030, tracer, config);

        verify(v30processor).processTransaction(transaction, worldUpdater, context, tracer, config);
    }

    @Test
    void calls034AsExpected() {
        final var transaction = wellKnownHapiCall();
        final var context = wellKnownContextWith(blocks, false, tinybarValues, systemContractGasCalculator);

        subject.process(transaction, worldUpdater, context, VERSION_034, tracer, config);

        verify(v34processor).processTransaction(transaction, worldUpdater, context, tracer, config);
    }

    @Test
    void calls038AsExpected() {
        final var transaction = wellKnownHapiCall();
        final var context = wellKnownContextWith(blocks, false, tinybarValues, systemContractGasCalculator);

        subject.process(transaction, worldUpdater, context, VERSION_038, tracer, config);

        verify(v38processor).processTransaction(transaction, worldUpdater, context, tracer, config);
    }
}
