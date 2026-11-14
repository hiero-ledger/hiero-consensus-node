// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.node.app.service.clpr.ReadableLedgerConfigurationStore;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierFactory;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;

/**
 * Factory-seam test: verifies that the verifier returned by {@link ClprVerifierFactory} for the
 * Canton built-in ({@code 0x172}) behaves identically to a directly-constructed
 * {@link CantonProofVerifier}, pinning the invariant that the factory injects no state into the
 * verifier — all context is drawn from {@link HandleContext} at call time.
 */
class CantonVerifierFactorySeamTest {

    private static final Bytes CHANNEL_ID = Bytes.wrap(new byte[32]);
    private static final Bytes EMPTY_PROOF = Bytes.EMPTY;

    @Test
    void factoryProducedCantonVerifierVerifiesConfigAndBundle() {
        final var cid = ContractID.newBuilder().contractNum(0x172L).build();
        final var viaFactory = new ClprVerifierFactory().getVerifier(cid);
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
    void factoryProducedCantonVerifierReadsLocalChainIdFromHandleContext() {
        final var cid = ContractID.newBuilder().contractNum(0x172L).build();
        final var viaFactory = new ClprVerifierFactory().getVerifier(cid);
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
