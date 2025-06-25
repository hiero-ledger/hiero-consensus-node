// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.HEVM_CREATION;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SUCCESS_RESULT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.processorsForAllCurrentEvmVersions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.service.contract.impl.exec.ActionSidecarContentTracer;
import com.hedera.node.app.service.contract.impl.exec.CallOutcome;
import com.hedera.node.app.service.contract.impl.exec.ContextQueryProcessor;
import com.hedera.node.app.service.contract.impl.exec.TransactionProcessor;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmContext;
import com.hedera.node.app.service.contract.impl.infra.HevmStaticTransactionFactory;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.EntityIdFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContextQueryProcessorTest {
    private static final Configuration CONFIGURATION = HederaTestConfigBuilder.createConfig();

    @Mock
    private QueryContext context;

    @Mock
    private TransactionProcessor processor;

    @Mock
    private HederaEvmContext hederaEvmContext;

    @Mock
    private ActionSidecarContentTracer tracer;

    @Mock
    private ProxyWorldUpdater proxyWorldUpdater;

    @Mock
    private HevmStaticTransactionFactory hevmStaticTransactionFactory;

    @Mock
    private EntityIdFactory entityIdFactory;

    @Test
    void callsComponentInfraAsExpectedForValidQuery() {
        final var processors = processorsForAllCurrentEvmVersions(processor);

        final var subject = new ContextQueryProcessor(
                context, hederaEvmContext, tracer, proxyWorldUpdater, hevmStaticTransactionFactory, processors);

        given(context.configuration()).willReturn(CONFIGURATION);
        given(context.query()).willReturn(Query.DEFAULT);
        given(hevmStaticTransactionFactory.fromHapiQuery(Query.DEFAULT)).willReturn(HEVM_CREATION);
        given(processor.processTransaction(HEVM_CREATION, proxyWorldUpdater, hederaEvmContext, tracer, CONFIGURATION))
                .willReturn(SUCCESS_RESULT);
        given(proxyWorldUpdater.entityIdFactory()).willReturn(entityIdFactory);
        final var protoResult = SUCCESS_RESULT.asQueryResult(proxyWorldUpdater);
        final var expectedResult =
                new CallOutcome(protoResult, SUCCESS, HEVM_CREATION.contractId(), null, null, null, null);
        assertEquals(expectedResult, subject.call());
    }
}
