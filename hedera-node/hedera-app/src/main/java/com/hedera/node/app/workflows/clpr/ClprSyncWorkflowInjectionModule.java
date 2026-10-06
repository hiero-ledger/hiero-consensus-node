// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import com.hedera.node.app.hapi.utils.blocks.NativeTssVerifier;
import com.hedera.node.app.hapi.utils.blocks.TssVerifier;
import com.hedera.node.app.service.clpr.ClprChannelLifecycle;
import com.hedera.node.app.spi.state.BlockProvenSnapshotProvider;
import com.hedera.node.app.state.BlockProvenStateAccessor;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Optional;
import javax.inject.Singleton;

/**
 * Module for CLPR sync processing. The {@code Supplier<AutoCloseableWrapper<State>>}
 * dependency is provided by {@code HandleWorkflowModule}.
 */
@Module
public abstract class ClprSyncWorkflowInjectionModule {
    @Binds
    abstract ClprSyncWorkflow bindClprSyncWorkflow(ClprRuntimeFacade clprRuntimeFacade);

    @Binds
    abstract ClprChannelLifecycle bindClprChannelLifecycle(ClprRuntimeFacade clprRuntimeFacade);

    @Binds
    abstract ClprRuntime bindClprRuntime(ClprRuntimeFacade clprRuntimeFacade);

    @Binds
    abstract ClprSynchronizer bindClprSynchronizer(ClprSynchronizerImpl clprSynchronizer);

    @Provides
    @Singleton
    static BlockProvenSnapshotProvider provideBlockProvenSnapshotProvider(
            @Nullable final BlockProvenStateAccessor accessor) {
        return accessor != null ? accessor : Optional::empty;
    }

    /**
     * Provides the {@link TssVerifier} used by CLPR consumers in this app component
     * (e.g. {@code ClprStateProofManager}, {@code ClprGetLedgerConfigurationHandler}).
     * The smart-contract module has its own provider for its own Dagger component; this
     * binding keeps the two consistent at the {@code NativeTssVerifier} level.
     */
    @Provides
    @Singleton
    static TssVerifier provideTssVerifier() {
        return new NativeTssVerifier();
    }
}
