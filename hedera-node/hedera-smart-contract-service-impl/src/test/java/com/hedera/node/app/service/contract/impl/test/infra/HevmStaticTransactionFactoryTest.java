// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.infra;

import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_GAS_LIMIT_EXCEEDED;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ALIASED_SOMEBODY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALL_DATA;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONTRACTS_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.assertFailsWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.ContractCallLocalQuery;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.infra.HevmStaticTransactionFactory;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.QueryContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HevmStaticTransactionFactoryTest {
    @Mock
    private GasCalculator gasCalculator;

    @Mock
    private QueryContext context;

    @Mock
    private ReadableAccountStore accountStore;

    private HevmStaticTransactionFactory subject;

    @BeforeEach
    void setUp() {
        given(context.configuration()).willReturn(DEFAULT_CONFIG);
        given(context.payer()).willReturn(SENDER_ID);
        subject = new HevmStaticTransactionFactory(context, gasCalculator);
    }

    @Test
    void fromQueryWorkWithSenderAndUsesPriorityAddress() {
        final var query = Query.newBuilder()
                .contractCallLocal(callLocalWith(b -> {
                    b.gas(21_000L);
                    b.senderId(SENDER_ID);
                    b.contractID(CALLED_CONTRACT_ID);
                    b.functionParameters(CALL_DATA);
                }))
                .build();
        given(context.createStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(accountStore.getContractById(CALLED_CONTRACT_ID)).willReturn(ALIASED_SOMEBODY);
        var transaction = subject.fromHapiQuery(query);
        assertThat(transaction.senderId()).isEqualTo(SENDER_ID);
        assertThat(transaction.relayerId()).isNull();
        assertThat(transaction.contractId()).isEqualTo(CALLED_CONTRACT_EVM_ADDRESS);
        assertThat(transaction.nonce()).isEqualTo(-1);
        assertThat(transaction.payload()).isEqualTo(CALL_DATA);
        assertThat(transaction.chainId()).isNull();
        assertThat(transaction.value()).isZero();
        assertThat(transaction.gasLimit()).isEqualTo(21_000L);
        assertThat(transaction.offeredGasPrice()).isEqualTo(1L);
        assertThat(transaction.maxGasAllowance()).isZero();
        assertThat(transaction.hapiCreation()).isNull();
    }

    @Test
    void fromQueryWorkWithNoSender() {
        final var transactionID =
                TransactionID.newBuilder().accountID(SENDER_ID).build();
        final var txBody =
                TransactionBody.newBuilder().transactionID(transactionID).build();
        final var payment = Transaction.newBuilder().body(txBody).build();
        final var queryHeader = QueryHeader.newBuilder().payment(payment).build();
        given(context.createStore(ReadableAccountStore.class)).willReturn(accountStore);

        final var query = Query.newBuilder()
                .contractCallLocal(callLocalWith(b -> {
                    b.gas(21_000L);
                    b.header(queryHeader);
                    b.contractID(CALLED_CONTRACT_ID);
                    b.functionParameters(CALL_DATA);
                }))
                .build();
        var transaction = subject.fromHapiQuery(query);
        assertThat(transaction.senderId()).isEqualTo(SENDER_ID);
        assertThat(transaction.relayerId()).isNull();
        assertThat(transaction.contractId()).isEqualTo(CALLED_CONTRACT_ID);
        assertThat(transaction.nonce()).isEqualTo(-1);
        assertThat(transaction.payload()).isEqualTo(CALL_DATA);
        assertThat(transaction.chainId()).isNull();
        assertThat(transaction.value()).isZero();
        assertThat(transaction.gasLimit()).isEqualTo(21_000L);
        assertThat(transaction.offeredGasPrice()).isEqualTo(1L);
        assertThat(transaction.maxGasAllowance()).isZero();
        assertThat(transaction.hapiCreation()).isNull();
    }

    @Test
    void fromQueryFailsWithNoContractCallLocal() {
        assertThatThrownBy(() -> subject.fromHapiQuery(Query.newBuilder().build()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void fromQueryFailsWithNoSenderAndNoHeader() {
        final var query = Query.newBuilder()
                .contractCallLocal(callLocalWith(b -> {
                    b.gas(21_000L);
                }))
                .build();
        assertThatThrownBy(() -> subject.fromHapiQuery(query)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void fromQueryFailsWithNoContractID() {
        final var query = Query.newBuilder()
                .contractCallLocal(callLocalWith(b -> {
                    b.gas(21_000L);
                    b.senderId(SENDER_ID);
                }))
                .build();
        assertThatThrownBy(() -> subject.fromHapiQuery(query)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void fromQueryFailsWithGasBelowFixedLowerBound() {
        assertCallFailsWith(ResponseCodeEnum.INSUFFICIENT_GAS, builder -> builder.gas(20_999L));
    }

    @Test
    void fromQueryFailsOverMaxGas() {
        assertCallFailsWith(MAX_GAS_LIMIT_EXCEEDED, b -> b.gas(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec() + 1));
    }

    @Test
    void fromQueryException() {
        given(context.createStore(ReadableAccountStore.class)).willReturn(accountStore);
        final var transaction = subject.fromHapiQueryException(
                Query.newBuilder()
                        .contractCallLocal(callLocalWith(b -> {
                            b.gas(21_000L);
                            b.senderId(SENDER_ID);
                            b.contractID(CALLED_CONTRACT_ID);
                            b.functionParameters(CALL_DATA);
                        }))
                        .build(),
                new HandleException(ResponseCodeEnum.INVALID_CONTRACT_ID));

        assertThat(transaction.senderId()).isEqualTo(SENDER_ID);
        assertThat(transaction.relayerId()).isNull();
        assertThat(transaction.contractId()).isEqualTo(CALLED_CONTRACT_ID);
        assertThat(transaction.nonce()).isEqualTo(-1);
        assertThat(transaction.payload()).isEqualTo(CALL_DATA);
        assertThat(transaction.chainId()).isNull();
        assertThat(transaction.value()).isZero();
        assertThat(transaction.gasLimit()).isEqualTo(21_000L);
        assertThat(transaction.offeredGasPrice()).isEqualTo(1L);
        assertThat(transaction.maxGasAllowance()).isZero();
        assertThat(transaction.hapiCreation()).isNull();
    }

    private void assertCallFailsWith(
            @NonNull final ResponseCodeEnum status, @NonNull final Consumer<ContractCallLocalQuery.Builder> spec) {
        assertFailsWith(
                status,
                () -> subject.fromHapiQuery(Query.newBuilder()
                        .contractCallLocal(callLocalWith(spec))
                        .build()));
    }

    private ContractCallLocalQuery callLocalWith(final Consumer<ContractCallLocalQuery.Builder> spec) {
        final var builder = ContractCallLocalQuery.newBuilder();
        spec.accept(builder);
        return builder.build();
    }
}
