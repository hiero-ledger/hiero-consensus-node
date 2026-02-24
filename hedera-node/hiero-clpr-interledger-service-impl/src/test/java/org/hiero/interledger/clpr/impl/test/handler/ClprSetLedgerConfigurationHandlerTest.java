// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.test.handler;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.roster.ReadableRosterStore;
import org.hiero.hapi.interledger.clpr.ClprSetLedgerConfigurationTransactionBody;
import org.hiero.hapi.interledger.state.clpr.ClprEndpoint;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprLocalLedgerMetadata;
import org.hiero.interledger.clpr.ClprStateProofUtils;
import org.hiero.interledger.clpr.WritableClprLedgerConfigurationStore;
import org.hiero.interledger.clpr.WritableClprMetadataStore;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;
import org.hiero.interledger.clpr.impl.handlers.ClprSetLedgerConfigurationHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
    private NodeInfo creatorInfo;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private com.hedera.node.config.VersionedConfiguration configuration;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private WritableClprMetadataStore metadataStore;

    @Mock
    private ReadableRosterStore rosterStore;

    @Mock
    private WritableClprLedgerConfigurationStore writableConfigStoreMock;

    private final Bytes rosterHash = Bytes.wrap("rosterHash");

    private ClprSetLedgerConfigurationHandler subject;

    @BeforeEach
    public void setUp() {
        setupHandlerBase();
        subject = new ClprSetLedgerConfigurationHandler(stateProofManager, configProvider);
        given(preHandleContext.creatorInfo()).willReturn(creatorInfo);
        given(creatorInfo.nodeId()).willReturn(1L);
        given(stateProofManager.clprEnabled()).willReturn(true);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(ClprConfig.class)).willReturn(new ClprConfig(true, 5000, true, 5, 6144));
        given(preHandleContext.isUserTransaction()).willReturn(true);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableClprLedgerConfigurationStore.class))
                .willReturn(writableLedgerConfigStore);
        given(storeFactory.writableStore(WritableClprMetadataStore.class)).willReturn(metadataStore);
        given(storeFactory.readableStore(ReadableRosterStore.class)).willReturn(rosterStore);
        given(rosterStore.getCurrentRosterHash()).willReturn(rosterHash);
    }

    @Test
    public void prehandleHappyPath() {
        final var txn = newTxnBuilder().withClprLedgerConfig(remoteClprConfig).build();
        given(stateProofManager.getLedgerConfiguration(any())).willReturn(null);
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
        given(stateProofManager.getLedgerConfiguration(any())).willReturn(null);
        given(stateProofManager.getLocalLedgerId()).willReturn(localClprLedgerId);
        given(preHandleContext.body()).willReturn(txn);

        // Then pre-handling succeeds without calling state proof validation
        assertThatCode(() -> subject.preHandle(preHandleContext)).doesNotThrowAnyException();

        // Verify state proof validation was never called for local transactions
        verify(stateProofManager, never()).validateStateProof(any());
    }

    @Test
    public void preHandleAllowsDevModeLocalBootstrap() {
        given(creatorInfo.nodeId()).willReturn(0L);
        final var txn = newTxnBuilder().withClprLedgerConfig(localClprConfig).build();

        given(stateProofManager.validateStateProof(any(ClprSetLedgerConfigurationTransactionBody.class)))
                .willReturn(true);
        given(stateProofManager.getLocalLedgerId()).willReturn(localClprLedgerId);
        given(stateProofManager.getLedgerConfiguration(localClprLedgerId)).willReturn(null);
        given(stateProofManager.readLedgerConfiguration(localClprLedgerId)).willReturn(null);
        given(preHandleContext.body()).willReturn(txn);

        assertThatCode(() -> subject.preHandle(preHandleContext)).doesNotThrowAnyException();
    }

    @Test
    public void preHandleTxnMissingLedgerId() {
        final var txn = newTxnBuilder().build();
        given(preHandleContext.body()).willReturn(txn);

        assertThatCode(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .hasMessageContaining(ResponseCodeEnum.WAITING_FOR_LEDGER_ID.name());
    }

    @Test
    public void preHandleTxnMissingEndpoints() {
        final var txn = newTxnBuilder().withLedgerId(remoteClprLedgerId).build();
        given(preHandleContext.body()).willReturn(txn);

        assertThatCode(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .hasMessageContaining(ResponseCodeEnum.WAITING_FOR_LEDGER_ID.name());
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
        given(stateProofManager.getLedgerConfiguration(localClprLedgerId)).willReturn(null);
        given(stateProofManager.readLedgerConfiguration(localClprLedgerId)).willReturn(localClprConfig);

        given(preHandleContext.body()).willReturn(txn);

        assertThatCode(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .hasMessageContaining(ResponseCodeEnum.INVALID_TRANSACTION.name());
    }

    @Test
    public void preHandleAllowsLocalSignerUpdateWhenDevModeDisabled() {
        given(creatorInfo.nodeId()).willReturn(0L);
        final var baseTimestamp = localClprConfig.timestampOrThrow();
        final var updatedTimestamp =
                baseTimestamp.copyBuilder().seconds(baseTimestamp.seconds() + 1).build();
        final var updatedConfig =
                localClprConfig.copyBuilder().timestamp(updatedTimestamp).build();
        final var txn = newTxnBuilder().withClprLedgerConfig(updatedConfig).build();

        given(stateProofManager.getLocalLedgerId()).willReturn(localClprLedgerId);
        given(stateProofManager.getLedgerConfiguration(localClprLedgerId)).willReturn(null);
        given(stateProofManager.readLedgerConfiguration(localClprLedgerId)).willReturn(localClprConfig);
        given(preHandleContext.body()).willReturn(txn);

        assertThatCode(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .hasMessageContaining(ResponseCodeEnum.INVALID_TRANSACTION.name());
    }

    @Test
    public void preHandleLedgerConfigurationNotNew() {
        final var txn = newTxnBuilder().withClprLedgerConfig(remoteClprConfig).build();

        given(stateProofManager.getLedgerConfiguration(remoteClprLedgerId))
                .willReturn(buildLocalClprStateProofWrapper(remoteClprConfig), (StateProof) null);
        given(stateProofManager.readLedgerConfiguration(remoteClprLedgerId)).willReturn(remoteClprConfig);
        given(stateProofManager.validateStateProof(any(ClprSetLedgerConfigurationTransactionBody.class)))
                .willReturn(true);
        given(stateProofManager.getLocalLedgerId()).willReturn(localClprLedgerId);
        given(preHandleContext.body()).willReturn(txn);

        assertThatCode(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .hasMessageContaining(ResponseCodeEnum.INVALID_TRANSACTION.name());
    }

    @Test
    public void preHandleStateProofInvalid() {
        final var invalidProof = buildInvalidStateProof(remoteClprConfig);
        final var txn = TransactionBody.newBuilder()
                .clprSetLedgerConfiguration(ClprSetLedgerConfigurationTransactionBody.newBuilder()
                        .ledgerConfigurationProof(invalidProof)
                        .build())
                .build();
        given(stateProofManager.getLocalLedgerId()).willReturn(localClprLedgerId);

        given(stateProofManager.validateStateProof(any(ClprSetLedgerConfigurationTransactionBody.class)))
                .willReturn(false);
        given(stateProofManager.readLedgerConfiguration(remoteClprLedgerId)).willReturn(null);
        given(preHandleContext.body()).willReturn(txn);
        given(preHandleContext.isUserTransaction()).willReturn(true);

        assertThatCode(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .hasMessageContaining(ResponseCodeEnum.CLPR_INVALID_STATE_PROOF.name());
    }

    @Test
    public void preHandleSyntheticSkipsStateProofValidation() {
        final var invalidProof = buildInvalidStateProof(remoteClprConfig);
        final var txn = TransactionBody.newBuilder()
                .clprSetLedgerConfiguration(ClprSetLedgerConfigurationTransactionBody.newBuilder()
                        .ledgerConfigurationProof(invalidProof)
                        .build())
                .build();
        given(stateProofManager.getLocalLedgerId()).willReturn(localClprLedgerId);

        given(stateProofManager.readLedgerConfiguration(remoteClprLedgerId)).willReturn(null);
        given(preHandleContext.body()).willReturn(txn);
        given(preHandleContext.isUserTransaction()).willReturn(false);

        assertThatCode(() -> subject.preHandle(preHandleContext)).doesNotThrowAnyException();
        verify(stateProofManager, never()).validateStateProof(any());
    }

    @Test
    public void preHandleWaitsWhenSnapshotMissing() {
        final var txn = newTxnBuilder().withClprLedgerConfig(remoteClprConfig).build();
        given(stateProofManager.getLocalLedgerId()).willReturn(null);
        given(preHandleContext.body()).willReturn(txn);

        assertThatCode(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .hasMessageContaining(ResponseCodeEnum.WAITING_FOR_LEDGER_ID.name());
    }

    @Test
    public void preHandleAllowsExternalWhenDevModeDisabled() {
        final var txn = newTxnBuilder().withClprLedgerConfig(remoteClprConfig).build();
        given(stateProofManager.getLocalLedgerId()).willReturn(localClprLedgerId);

        given(preHandleContext.body()).willReturn(txn);

        assertThatCode(() -> subject.preHandle(preHandleContext)).doesNotThrowAnyException();
    }

    @Test
    public void preHandleRejectsWhenTimestampNotMonotonic() {
        final var txn = newTxnBuilder().withClprLedgerConfig(remoteClprConfig).build();
        given(stateProofManager.getLocalLedgerId()).willReturn(localClprLedgerId);

        given(stateProofManager.getLedgerConfiguration(remoteClprLedgerId))
                .willReturn(buildLocalClprStateProofWrapper(remoteClprConfig));
        given(stateProofManager.readLedgerConfiguration(remoteClprLedgerId)).willReturn(remoteClprConfig);
        given(stateProofManager.validateStateProof(any(ClprSetLedgerConfigurationTransactionBody.class)))
                .willReturn(true);
        given(preHandleContext.body()).willReturn(txn);

        assertThatCode(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .hasMessageContaining(ResponseCodeEnum.INVALID_TRANSACTION.name());
    }

    @Test
    public void preHandleAllowsDevModeExternalSubmission() {
        final var baseSeconds = remoteClprConfig.timestampOrThrow().seconds();
        final var updatedTimestamp = remoteClprConfig
                .timestampOrThrow()
                .copyBuilder()
                .seconds(baseSeconds + 20)
                .build();
        final var updatedConfig =
                remoteClprConfig.copyBuilder().timestamp(updatedTimestamp).build();
        assertThat(localClprLedgerId.equals(updatedConfig.ledgerId())).isFalse();
        final var existingProof = buildLocalClprStateProofWrapper(remoteClprConfig);
        assertThat(ClprStateProofUtils.extractConfiguration(existingProof)
                        .timestampOrThrow()
                        .seconds())
                .isLessThan(updatedConfig.timestampOrThrow().seconds());
        given(stateProofManager.getLocalLedgerId()).willReturn(localClprLedgerId);
        given(stateProofManager.getLedgerConfiguration(remoteClprLedgerId))
                .willReturn(existingProof, (StateProof) null);

        given(stateProofManager.validateStateProof(any(ClprSetLedgerConfigurationTransactionBody.class)))
                .willReturn(true);
        final var txn = newTxnBuilder().withClprLedgerConfig(updatedConfig).build();
        given(preHandleContext.body()).willReturn(txn);

        assertThatCode(() -> subject.preHandle(preHandleContext)).doesNotThrowAnyException();
    }

    @Test
    public void handleUpdatesMetadataWithRosterHashAndStableLedgerId() throws Exception {
        final var txn = TransactionBody.newBuilder()
                .clprSetLedgerConfiguration(ClprSetLedgerConfigurationTransactionBody.newBuilder()
                        .ledgerConfigurationProof(buildLocalClprStateProofWrapper(localClprConfig))
                        .build())
                .build();
        given(handleContext.body()).willReturn(txn);
        given(metadataStore.get()).willReturn(null);

        subject.handle(handleContext);

        final var captor = ArgumentCaptor.forClass(ClprLocalLedgerMetadata.class);
        verify(metadataStore).put(captor.capture());
        final var persisted = captor.getValue();
        assertThat(persisted.ledgerIdOrThrow().ledgerId())
                .isEqualTo(localClprConfig.ledgerId().ledgerId());
        assertThat(persisted.rosterHash()).isEqualTo(rosterHash);
    }

    @Test
    public void handleUpdatesConfigWhenRosterHashDoesNotAdvance() {
        final var txn = TransactionBody.newBuilder()
                .clprSetLedgerConfiguration(ClprSetLedgerConfigurationTransactionBody.newBuilder()
                        .ledgerConfigurationProof(buildLocalClprStateProofWrapper(localClprConfig))
                        .build())
                .build();
        final var existingMetadata = ClprLocalLedgerMetadata.newBuilder()
                .ledgerId(localClprConfig.ledgerId())
                .rosterHash(rosterHash)
                .build();
        given(handleContext.body()).willReturn(txn);
        given(metadataStore.get()).willReturn(existingMetadata);
        given(storeFactory.writableStore(WritableClprLedgerConfigurationStore.class))
                .willReturn(writableConfigStoreMock);

        assertThatCode(() -> subject.handle(handleContext)).doesNotThrowAnyException();
        verify(metadataStore, never()).put(any());
        verify(writableConfigStoreMock).put(any());
    }

    @Test
    @DisplayName("Handle reuses existing ledgerId and advances roster hash on change")
    void handleReusesExistingLedgerIdWhenRosterChanges() throws Exception {
        final var existingMetadata = ClprLocalLedgerMetadata.newBuilder()
                .ledgerId(ClprLedgerId.newBuilder()
                        .ledgerId(Bytes.wrap("existing-ledger"))
                        .build())
                .rosterHash(Bytes.wrap("old-roster"))
                .build();
        given(metadataStore.get()).willReturn(existingMetadata);
        given(storeFactory.writableStore(WritableClprLedgerConfigurationStore.class))
                .willReturn(writableConfigStoreMock);

        final var txn = TransactionBody.newBuilder()
                .clprSetLedgerConfiguration(ClprSetLedgerConfigurationTransactionBody.newBuilder()
                        .ledgerConfigurationProof(buildLocalClprStateProofWrapper(localClprConfig))
                        .build())
                .build();
        given(handleContext.body()).willReturn(txn);

        subject.handle(handleContext);

        final var metadataCaptor = ArgumentCaptor.forClass(ClprLocalLedgerMetadata.class);
        verify(metadataStore).put(metadataCaptor.capture());
        final var persistedMetadata = metadataCaptor.getValue();
        assertThat(persistedMetadata.ledgerId().ledgerId())
                .isEqualTo(existingMetadata.ledgerId().ledgerId());
        assertThat(persistedMetadata.rosterHash()).isEqualTo(rosterHash);

        final var configCaptor = ArgumentCaptor.forClass(ClprLedgerConfiguration.class);
        verify(writableConfigStoreMock).put(configCaptor.capture());
        assertThat(configCaptor.getValue().ledgerId()).isEqualTo(localClprConfig.ledgerId());
    }

    @Test
    @DisplayName("Pure checks should reject user-sourced updates targeting the local ledgerId")
    void pureChecksRejectsRemoteAttemptToUpdateLocalLedgerConfig() {
        // Purpose: Document the intended guard in pureChecks: user/remote submissions must not replace the local
        // ledger configuration. This is where the handler already has state and can compare against local metadata.
        final var fresherLocalConfig = localClprConfig
                .copyBuilder()
                .timestamp(Timestamp.newBuilder()
                        .seconds(localClprConfig.timestampOrThrow().seconds() + 10)
                        .build())
                .build();
        final var txn = newTxnBuilder().withClprLedgerConfig(fresherLocalConfig).build();

        given(pureChecksContext.body()).willReturn(txn);
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(ClprConfig.class)).willReturn(new ClprConfig(true, 5000, true, 5, 6144));
        given(stateProofManager.getLocalLedgerId()).willReturn(localClprLedgerId);
        given(stateProofManager.readLedgerConfiguration(localClprLedgerId)).willReturn(localClprConfig);
        given(stateProofManager.validateStateProof(any(ClprSetLedgerConfigurationTransactionBody.class)))
                .willReturn(true);

        assertThatCode(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .hasMessageContaining(ResponseCodeEnum.INVALID_TRANSACTION.name());
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
            this.timestamp = ledgerConfig.timestampOrElse(Timestamp.DEFAULT);
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

            final var stateProof = buildLocalClprStateProofWrapper(localClprConfig);

            final var bodyInternals = ClprSetLedgerConfigurationTransactionBody.newBuilder()
                    .ledgerConfigurationProof(stateProof)
                    .build();
            return TransactionBody.newBuilder()
                    .clprSetLedgerConfiguration(bodyInternals)
                    .build();
        }
    }
}
