// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.verifier;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_VERIFIER_CONTRACT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.node.app.service.clpr.ReadableLedgerConfigurationStore;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierFactory;
import com.hedera.node.app.service.clpr.impl.verifier.EvmClprVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProofVerifier;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClprVerifierFactoryTest {

    private static final Bytes CHANNEL_ID = Bytes.wrap(new byte[32]);
    private static final Bytes EMPTY_PROOF = Bytes.EMPTY;

    private final ClprVerifierFactory subject = new ClprVerifierFactory();

    @Test
    @DisplayName("getVerifier returns an EvmClprVerifier bound to a contractNum verifier contract")
    void resolvesContractNumVerifier() {
        final var contractId = ContractID.newBuilder()
                .shardNum(0)
                .realmNum(0)
                .contractNum(5001)
                .build();
        assertThat(subject.getVerifier(contractId)).isInstanceOf(EvmClprVerifier.class);
    }

    @Test
    @DisplayName("getVerifier returns an EvmClprVerifier bound to an evmAddress verifier contract")
    void resolvesEvmAddressVerifier() {
        final var evmAddress =
                Bytes.wrap(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x13, (byte) 0x89});
        final var contractId = ContractID.newBuilder().evmAddress(evmAddress).build();
        assertThat(subject.getVerifier(contractId)).isInstanceOf(EvmClprVerifier.class);
    }

    @Test
    @DisplayName("getVerifier rejects a verifier contract with no identifier")
    void rejectsContractIdWithoutIdentifier() {
        final var contractId = ContractID.newBuilder().build();
        assertThatThrownBy(() -> subject.getVerifier(contractId))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_VERIFIER_CONTRACT));
    }

    @Test
    @DisplayName("getVerifier resolves a precompile built-in (0x16f Besu) to an EvmClprVerifier")
    void precompileBuiltInResolvesToEvmVerifier() {
        final var cid = ContractID.newBuilder().contractNum(0x16fL).build();
        assertThat(subject.getVerifier(cid)).isInstanceOf(EvmClprVerifier.class);
    }

    @Test
    @DisplayName("getVerifier falls through to EvmClprVerifier for a custom (non-built-in) contract num")
    void customContractFallsThroughToEvm() {
        final var cid = ContractID.newBuilder().contractNum(0x999L).build();
        assertThat(subject.getVerifier(cid)).isInstanceOf(EvmClprVerifier.class);
    }

    @Test
    @DisplayName("getVerifier throws CLPR_INVALID_VERIFIER_CONTRACT for an empty ContractID")
    void emptyIdThrowsInvalidVerifierContract() {
        final var cid = ContractID.newBuilder().build();
        assertThatThrownBy(() -> subject.getVerifier(cid))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_VERIFIER_CONTRACT));
    }

    @Test
    @DisplayName("Canton (0x172) built-in resolves to CantonProofVerifier via contract num")
    void cantonResolvesToNativeVerifierByNum() {
        final var cid = ContractID.newBuilder().contractNum(0x172L).build();
        assertThat(subject.getVerifier(cid)).isInstanceOf(CantonProofVerifier.class);
    }

    @Test
    @DisplayName("Canton (0x172) built-in resolves to CantonProofVerifier via evm address")
    void cantonResolvesToNativeVerifierByEvmAddress() {
        final var addr =
                Bytes.wrap(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01, (byte) 0x72});
        final var cid = ContractID.newBuilder().evmAddress(addr).build();
        assertThat(subject.getVerifier(cid)).isInstanceOf(CantonProofVerifier.class);
    }

    @Test
    @DisplayName("the factory injects no state into the Canton verifier: config verification matches a direct instance")
    void factoryProducedCantonVerifierVerifiesConfigAndBundle() {
        final var cid = ContractID.newBuilder().contractNum(0x172L).build();
        final var viaFactory = subject.getVerifier(cid);
        final var direct = new CantonProofVerifier();

        // Stub a HandleContext that provides a valid local chain ID.
        final var ctx = stubContextWithChainId("eip155:298");

        // Both instances must throw HandleException(CLPR_VERIFIER_CONFIG_FAILED) for the same
        // invalid input, proving no factory-supplied state changes verification behavior.
        final var exViaFactory =
                catchHandleException(() -> viaFactory.verifyConfig(EMPTY_PROOF, CHANNEL_ID, EMPTY_PROOF, ctx));
        final var exDirect = catchHandleException(() -> direct.verifyConfig(EMPTY_PROOF, CHANNEL_ID, EMPTY_PROOF, ctx));

        assertThat(exViaFactory).isNotNull().isInstanceOf(HandleException.class);
        assertThat(exDirect).isNotNull().isInstanceOf(HandleException.class);
        assertThat(exViaFactory.getStatus()).isEqualTo(CLPR_VERIFIER_CONFIG_FAILED);
        assertThat(exViaFactory.getStatus()).isEqualTo(exDirect.getStatus());
    }

    @Test
    @DisplayName("the Canton verifier reads the local chain id from the HandleContext at call time")
    void factoryProducedCantonVerifierReadsLocalChainIdFromHandleContext() {
        final var cid = ContractID.newBuilder().contractNum(0x172L).build();
        final var viaFactory = subject.getVerifier(cid);
        final var direct = new CantonProofVerifier();

        // When the HandleContext cannot provide config, both must fail identically.
        final var ctx = mock(HandleContext.class);
        final var storeFactory = mock(StoreFactory.class);
        when(ctx.storeFactory()).thenReturn(storeFactory);
        when(storeFactory.readableStore(ReadableLedgerConfigurationStore.class))
                .thenThrow(new RuntimeException("store unavailable"));

        final var exViaFactory =
                catchHandleException(() -> viaFactory.verifyConfig(EMPTY_PROOF, CHANNEL_ID, EMPTY_PROOF, ctx));
        final var exDirect = catchHandleException(() -> direct.verifyConfig(EMPTY_PROOF, CHANNEL_ID, EMPTY_PROOF, ctx));

        assertThat(exViaFactory).isNotNull();
        assertThat(exViaFactory.getStatus()).isEqualTo(CLPR_VERIFIER_CONFIG_FAILED);
        assertThat(exViaFactory.getStatus()).isEqualTo(exDirect.getStatus());
    }

    private static HandleContext stubContextWithChainId(final String chainId) {
        final var ctx = mock(HandleContext.class);
        final var storeFactory = mock(StoreFactory.class);
        final var configStore = mock(ReadableLedgerConfigurationStore.class);
        when(ctx.storeFactory()).thenReturn(storeFactory);
        when(storeFactory.readableStore(ReadableLedgerConfigurationStore.class)).thenReturn(configStore);
        when(configStore.getConfiguration())
                .thenReturn(
                        ClprLedgerConfiguration.newBuilder().chainId(chainId).build());
        return ctx;
    }

    private static HandleException catchHandleException(final ThrowableAssert.ThrowingCallable callable) {
        try {
            callable.call();
            return null;
        } catch (final HandleException e) {
            return e;
        } catch (final Throwable t) {
            throw new AssertionError(
                    "Expected HandleException but got " + t.getClass().getSimpleName(), t);
        }
    }
}
