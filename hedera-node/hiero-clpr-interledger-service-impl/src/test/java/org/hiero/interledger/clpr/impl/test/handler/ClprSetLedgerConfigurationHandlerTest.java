// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.test.handler;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import org.hiero.hapi.interledger.clpr.ClprSetLedgerConfigurationTransactionBody;
import org.hiero.hapi.interledger.state.clpr.ClprEndpoint;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;
import org.hiero.interledger.clpr.impl.handlers.ClprSetLedgerConfigurationHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ClprSetLedgerConfigurationHandlerTest extends ClprHandlerTestBase {

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private PreHandleContext preHandleContext;

    @Mock
    private HandleContext handleContext;

    @Mock
    private ClprStateProofManager stateProofManager;

    @Mock
    private NetworkInfo networkInfo;

    @Mock
    private NodeInfo creatorInfo;

    @Mock
    private NodeInfo selfNodeInfo;

    private ClprSetLedgerConfigurationHandler subject;

    @BeforeEach
    public void setUp() {
        setupHandlerBase();
        subject = new ClprSetLedgerConfigurationHandler(stateProofManager, networkInfo);
        given(preHandleContext.creatorInfo()).willReturn(creatorInfo);
        given(networkInfo.selfNodeInfo()).willReturn(selfNodeInfo);
        given(creatorInfo.nodeId()).willReturn(1L);
        given(selfNodeInfo.nodeId()).willReturn(0L);
    }

    @Test
    public void prehandleHappyPath() {
        final var txn = newTxnBuilder().withClprLedgerConfig(remoteClprConfig).build();
        given(stateProofManager.getLocalLedgerId()).willReturn(localClprLedgerId);
        given(stateProofManager.validateStateProof(any(ClprSetLedgerConfigurationTransactionBody.class)))
                .willReturn(true);
        given(preHandleContext.body()).willReturn(txn);

        assertThatCode(() -> subject.preHandle(preHandleContext)).doesNotThrowAnyException();
    }

    @Test
    public void prehandleHappyPathLocal() {
        // Given a transaction from the self-node
        given(creatorInfo.nodeId()).willReturn(0L);
        final var txn = newTxnBuilder().withClprLedgerConfig(remoteClprConfig).build();
        given(stateProofManager.getLocalLedgerId()).willReturn(localClprLedgerId);
        given(preHandleContext.body()).willReturn(txn);

        // Then pre-handling succeeds without calling state proof validation
        assertThatCode(() -> subject.preHandle(preHandleContext)).doesNotThrowAnyException();

        // Verify state proof validation was never called for local transactions
        verify(stateProofManager, never()).validateStateProof(any());
    }

    @Test
    public void preHandleTxnMissingLedgerId() {
        final var txn = newTxnBuilder().build();
        given(preHandleContext.body()).willReturn(txn);

        assertThatCode(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .hasMessageContaining(ResponseCodeEnum.INVALID_TRANSACTION.name());
    }

    @Test
    public void preHandleTxnMissingEndpoints() {
        final var txn = newTxnBuilder().withLedgerId(remoteClprLedgerId).build();
        given(preHandleContext.body()).willReturn(txn);

        assertThatCode(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .hasMessageContaining(ResponseCodeEnum.INVALID_TRANSACTION.name());
    }

    @Test
    public void preHandleLocalLedgerIdNotDetermined() {
        final var txn = newTxnBuilder().withClprLedgerConfig(remoteClprConfig).build();
        given(stateProofManager.getLocalLedgerId()).willReturn(ClprLedgerId.DEFAULT);
        given(preHandleContext.body()).willReturn(txn);

        assertThatCode(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .hasMessageContaining(ResponseCodeEnum.WAITING_FOR_LEDGER_ID.name());
    }

    @Test
    public void preHandleLocalLedgerConfigurationSetFails() {
        final var txn = newTxnBuilder().withClprLedgerConfig(localClprConfig).build();
        given(stateProofManager.getLocalLedgerId()).willReturn(localClprLedgerId);
        given(preHandleContext.body()).willReturn(txn);

        assertThatCode(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .hasMessageContaining(ResponseCodeEnum.INVALID_TRANSACTION.name());
    }

    @Test
    public void preHandleLedgerConfigurationNotNew() {
        final var txn = newTxnBuilder().withClprLedgerConfig(remoteClprConfig).build();
        given(stateProofManager.getLedgerConfiguration(remoteClprLedgerId)).willReturn(remoteClprConfig);
        given(stateProofManager.getLocalLedgerId()).willReturn(localClprLedgerId);
        given(preHandleContext.body()).willReturn(txn);

        assertThatCode(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .hasMessageContaining(ResponseCodeEnum.INVALID_TRANSACTION.name());
    }

    @Test
    public void preHandleStateProofInvalid() {
        final var txn = newTxnBuilder().withClprLedgerConfig(remoteClprConfig).build();
        given(stateProofManager.getLocalLedgerId()).willReturn(localClprLedgerId);
        given(stateProofManager.validateStateProof(any(ClprSetLedgerConfigurationTransactionBody.class)))
                .willReturn(false);
        given(preHandleContext.body()).willReturn(txn);

        assertThatCode(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .hasMessageContaining(ResponseCodeEnum.CLPR_INVALID_STATE_PROOF.name());
    }

    private TxnBuilder newTxnBuilder() {
        return new TxnBuilder();
    }

    public class TxnBuilder {
        private ClprLedgerId ledgerId = ClprLedgerId.DEFAULT;
        private List<ClprEndpoint> localEndpoints = new ArrayList<>();
        private Timestamp timestamp = Timestamp.DEFAULT;

        public TxnBuilder withClprLedgerConfig(@NonNull final ClprLedgerConfiguration ledgerConfig) {
            this.ledgerId = requireNonNull(ledgerConfig).ledgerId();
            this.localEndpoints = ledgerConfig.endpoints();
            return this;
        }

        public TxnBuilder withLedgerId(@NonNull final ClprLedgerId ledgerId) {
            this.ledgerId = requireNonNull(ledgerId);
            return this;
        }

        public TxnBuilder withLedgerId(final Bytes ledgerId) {
            this.ledgerId =
                    ClprLedgerId.newBuilder().ledgerId(requireNonNull(ledgerId)).build();
            return this;
        }

        public TxnBuilder withLedgerId(final byte[] ledgerId) {
            return withLedgerId(Bytes.wrap(requireNonNull(ledgerId)));
        }

        public TxnBuilder withLocalEndpoints(@NonNull final List<ClprEndpoint> localEndpoints) {
            this.localEndpoints = requireNonNull(localEndpoints);
            return this;
        }

        public TxnBuilder withLocalEndpoint(@NonNull final ServiceEndpoint endpoint, Bytes certificate) {
            localEndpoints.add(ClprEndpoint.newBuilder()
                    .endpoint(requireNonNull(endpoint))
                    .signingCertificate(requireNonNull(certificate))
                    .build());
            return this;
        }

        public TxnBuilder withTimestamp(@NonNull final Timestamp timestamp) {
            this.timestamp = requireNonNull(timestamp);
            return this;
        }

        public TransactionBody build() {
            final ClprLedgerConfiguration localClprConfig = ClprLedgerConfiguration.newBuilder()
                    .ledgerId(ledgerId)
                    .endpoints(localEndpoints)
                    .timestamp(timestamp)
                    .build();
            final var bodyInternals = ClprSetLedgerConfigurationTransactionBody.newBuilder()
                    .ledgerConfiguration(localClprConfig)
                    .build();
            return TransactionBody.newBuilder()
                    .clprSetLedgerConfiguration(bodyInternals)
                    .build();
        }
    }
}
