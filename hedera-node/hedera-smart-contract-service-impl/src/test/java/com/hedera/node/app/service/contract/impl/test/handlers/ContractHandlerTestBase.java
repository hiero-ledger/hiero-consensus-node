// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.fixtures.TransactionFactory;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A base class which aims to combine common logic for all Contract handlers.
 */
@SuppressWarnings({"NewClassNamingConvention", "MissingJavadoc"})
@ExtendWith(MockitoExtension.class)
public class ContractHandlerTestBase implements TransactionFactory {
    private static final Function<String, Key.Builder> KEY_BUILDER =
            value -> Key.newBuilder().ed25519(Bytes.wrap(value.getBytes()));
    private static final String A_NAME = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static final String B_NAME = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private static final String C_NAME = "cccccccccccccccccccccccccccccccc";

    @SuppressWarnings("MissingJavadoc")
    public static final Key A_THRESHOLD_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    KEY_BUILDER.apply(A_NAME).build(),
                                    KEY_BUILDER.apply(B_NAME).build(),
                                    KEY_BUILDER.apply(C_NAME).build())
                            .build()))
            .build();

    @SuppressWarnings("MissingJavadoc")
    public static final Key A_COMPLEX_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    KEY_BUILDER.apply(A_NAME).build(),
                                    KEY_BUILDER.apply(B_NAME).build(),
                                    A_THRESHOLD_KEY)))
            .build();

    @SuppressWarnings("MissingJavadoc")
    public static final Key B_COMPLEX_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    KEY_BUILDER.apply(A_NAME).build(),
                                    KEY_BUILDER.apply(B_NAME).build(),
                                    A_COMPLEX_KEY)))
            .build();

    @SuppressWarnings("MissingJavadoc")
    public static final Key C_COMPLEX_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    KEY_BUILDER.apply(A_NAME).build(),
                                    KEY_BUILDER.apply(B_NAME).build(),
                                    B_COMPLEX_KEY)))
            .build();

    protected final AccountID payer = asAccount("0.0.3");
    protected final AccountID autoRenewAccountId = asAccount("0.0.10001");
    protected final Key payerKey = A_COMPLEX_KEY;
    protected final Key adminKey = B_COMPLEX_KEY;
    protected final Key adminContractKey =
            Key.newBuilder().contractID(asContract("0.0.10002")).build();
    protected final Key autoRenewKey = C_COMPLEX_KEY;
    protected final Timestamp consensusTimestamp =
            Timestamp.newBuilder().seconds(1_234_567L).build();
    protected final ContractID targetContract =
            ContractID.newBuilder().contractNum(9_999L).build();
    protected final ContractID invalidContract =
            ContractID.newBuilder().evmAddress(Bytes.fromHex("abcdabcd")).build();

    @Mock
    private Bytes evmAddress;

    protected final ContractID targetContractWithEvmAddress =
            ContractID.newBuilder().evmAddress(evmAddress).build();

    @Mock
    protected Account payerAccount;

    @Mock
    protected ReadableAccountStore accountStore;

    @BeforeEach
    void commonSetUp() {
        try {
            setUpPayer();
        } catch (PreCheckException e) {
            throw new RuntimeException(e);
        }
    }

    protected void basicMetaAssertions(final PreHandleContext context, final int nonPayerKeySize) {
        assertThat(context.requiredNonPayerKeys()).hasSize(nonPayerKeySize);
    }

    protected void setUpPayer() throws PreCheckException {
        lenient().when(accountStore.getAccountById(payer)).thenReturn(payerAccount);
        lenient().when(payerAccount.key()).thenReturn(payerKey);
    }
}
