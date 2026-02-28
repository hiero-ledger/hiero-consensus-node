// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.token.NativeCoinDecimals;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.config.data.NativeCoinConfig;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class V0710TokenSchema extends Schema<SemanticVersion> {

    private static final Logger log = LogManager.getLogger(V0710TokenSchema.class);

    public static final String NATIVE_COIN_DECIMALS_KEY = "NATIVE_COIN_DECIMALS";
    public static final int NATIVE_COIN_DECIMALS_STATE_ID =
            SingletonType.TOKENSERVICE_I_NATIVE_COIN_DECIMALS.protoOrdinal();
    public static final String NATIVE_COIN_DECIMALS_STATE_LABEL =
            computeLabel(TokenService.NAME, NATIVE_COIN_DECIMALS_KEY);

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(71).patch(0).build();

    public V0710TokenSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @SuppressWarnings("rawtypes")
    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.singleton(
                NATIVE_COIN_DECIMALS_STATE_ID, NATIVE_COIN_DECIMALS_KEY, NativeCoinDecimals.PROTOBUF));
    }

    @Override
    public void migrate(@NonNull final MigrationContext<SemanticVersion> ctx) {
        // Genesis case: decimals are written by TokenServiceImpl.doGenesisSetup()
        if (ctx.isGenesis()) {
            return;
        }

        // Non-genesis: the singleton MUST exist in previous state
        if (!ctx.previousStates().contains(NATIVE_COIN_DECIMALS_STATE_ID)) {
            throw new IllegalStateException("State singleton missing: " + NATIVE_COIN_DECIMALS_KEY
                    + " — cannot determine decimal configuration. State may be corrupted.");
        }

        // Read persisted value and compare to current config
        final var nativeCoinDecimals = ctx.previousStates()
                .<NativeCoinDecimals>getSingleton(NATIVE_COIN_DECIMALS_STATE_ID)
                .get();
        if (nativeCoinDecimals == null) {
            throw new IllegalStateException("State singleton value is null: " + NATIVE_COIN_DECIMALS_KEY
                    + " — cannot determine decimal configuration. State may be corrupted.");
        }
        final var persistedDecimals = nativeCoinDecimals.decimals();
        final int configDecimals =
                ctx.appConfig().getConfigData(NativeCoinConfig.class).decimals();

        if (configDecimals != persistedDecimals) {
            log.warn(
                    "nativeCoin.decimals config ({}) differs from genesis value ({}). Using genesis value: {}",
                    configDecimals,
                    persistedDecimals,
                    persistedDecimals);
        }
    }
}
