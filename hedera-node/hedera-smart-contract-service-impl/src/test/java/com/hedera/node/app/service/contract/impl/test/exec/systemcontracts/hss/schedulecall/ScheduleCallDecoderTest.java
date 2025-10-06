// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hss.schedulecall;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_BESU_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleCreateTransactionBody;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.schedulecall.ScheduleCallDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.schedulecall.ScheduleCallTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hss.schedulecall.ScheduleCallTranslatorTest.TestFunction;
import com.swirlds.config.api.Configuration;
import java.util.List;
import java.util.Set;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;

public class ScheduleCallDecoderTest extends CallAttemptTestBase {

    @Mock
    private ContractID contractId;

    @Mock
    private SchedulableTransactionBody scheduleTrx;

    @Mock
    private Key key;

    @Mock
    private AccountID sender;

    @Mock
    private HssCallAttempt attempt;

    @Mock
    private ScheduleCreateTransactionBody scheduleCreateTrx;

    @Mock
    private Configuration configuration;

    @Mock
    private ScheduleCallTranslator translator;

    @Mock
    private TransactionID transactionId;

    private final ScheduleCallDecoder subject = new ScheduleCallDecoder();

    @Test
    public void testScheduledTransactionBodyFor() {
        // given:
        final var gasLimit = 1_000_000L;
        final var value = 0L;
        byte[] callData = new byte[] {1, 2, 3, 4};
        // when:
        final var body = subject.scheduledTransactionBodyFor(contractId, gasLimit, value, callData);
        // then:
        assertTrue(body.hasContractCall());
        assertTrue(body.contractCallOrThrow().hasContractID());
        assertEquals(contractId, body.contractCallOrThrow().contractIDOrThrow());
        assertEquals(gasLimit, body.contractCallOrThrow().gas());
        assertEquals(value, body.contractCallOrThrow().amount());
        assertEquals(
                com.hedera.pbj.runtime.io.buffer.Bytes.wrap(callData),
                body.contractCallOrThrow().functionParameters());
    }

    @Test
    public void testScheduleCreateTransactionBodyFor() {
        // given:
        final var keys = Set.of(key);
        final var expirySecond = 1_000L;
        // when:
        final var body = subject.scheduleCreateTransactionBodyFor(scheduleTrx, keys, expirySecond, sender, false);
        // then:
        assertTrue(body.hasScheduledTransactionBody());
        assertEquals(scheduleTrx, body.scheduledTransactionBody());
        assertTrue(body.hasAdminKey());
        assertEquals(key, body.adminKey());
        assertTrue(body.hasExpirationTime());
        assertEquals(Timestamp.newBuilder().seconds(expirySecond).build(), body.expirationTime());
        assertTrue(body.hasPayerAccountID());
        assertEquals(sender, body.payerAccountID());
    }

    @Test
    public void testTransactionBodyFor() {
        given(nativeOperations.getTransactionID()).willReturn(transactionId);
        attempt = createHssCallAttempt(
                scheduleCallFunctions().getFirst().input(),
                OWNER_BESU_ADDRESS,
                false,
                configuration,
                List.of(translator));
        // when:
        final var body = subject.transactionBodyFor(attempt, scheduleCreateTrx);
        // then:
        assertTrue(body.hasTransactionID());
        assertEquals(transactionId, body.transactionID());
        assertTrue(body.hasScheduleCreate());
        assertEquals(scheduleCreateTrx, body.scheduleCreateOrThrow());
    }

    public static List<TestFunction> scheduleCallFunctions() {
        return ScheduleCallTranslatorTest.scheduleCallFunctions();
    }

    @ParameterizedTest
    @MethodSource("scheduleCallFunctions")
    public void testDecodeScheduleCall(TestFunction data) {
        // given:
        if (data.senderParameter() instanceof Address) {
            given(addressIdConverter.convertSender(data.senderAddress())).willReturn(sender);
        } else {
            given(addressIdConverter.convert((com.esaulpaugh.headlong.abi.Address) data.senderParameter()))
                    .willReturn(sender);
        }

        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(nativeOperations.getTransactionID()).willReturn(transactionId);
        attempt = createHssCallAttempt(data.input(), OWNER_BESU_ADDRESS, false, configuration, List.of(translator));
        // when:
        final var body = subject.decodeScheduleCall(attempt, Set.of(key));
        // then:
        assertTrue(body.hasTransactionID());
        assertEquals(transactionId, body.transactionID());
        assertTrue(body.hasScheduleCreate());
    }
}
