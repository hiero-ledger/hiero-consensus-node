// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.utils;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.dsl.EvmAddressableEntity;
import com.hedera.services.bdd.spec.dsl.SpecEntity;
import com.hedera.services.bdd.spec.dsl.SpecEntityRegistrar;
import com.hedera.services.bdd.spec.dsl.entities.SpecKey;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class DslUtils {
    private DslUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    public static final Key PBJ_IMMUTABILITY_SENTINEL_KEY =
            Key.newBuilder().keyList(KeyList.DEFAULT).build();
    public static final com.hederahashgraph.api.proto.java.Key PROTO_IMMUTABILITY_SENTINEL_KEY =
            fromPbj(PBJ_IMMUTABILITY_SENTINEL_KEY);

    /**
     * Substitutes spec entities with their EVM applicable types in the given arguments, as follows:
     * <ul>
     *     <li>Substitutes {@link EvmAddressableEntity} entities with their addresses.</li>
     *     <li>Substitutes {@link SpecKey} entities with their raw bytes.</li>
     * </ul>
     *
     * @param network the network
     * @param args the arguments
     * @return the arguments with entities substituted with their EVM applicable types
     */
    public static Object[] withSubstitutedTypes(@NonNull final HederaNetwork network, @NonNull final Object... args) {
        return Arrays.stream(args)
                .map(arg -> switch (arg) {
                    case EvmAddressableEntity evmAddressableEntity -> evmAddressableEntity.addressOn(network);
                    case SpecKey key -> key.asEncodedOn(network);
                    default -> arg;
                })
                .toArray(Object[]::new);
    }

    /**
     * Returns a registrar that ensures that a spec entity is registered at most once per spec.
     *
     * @param registrar the registrar
     * @return a registrar that ensures that a spec entity is registered at most once per spec
     */
    public static SpecEntityRegistrar atMostOnce(@NonNull final SpecEntityRegistrar registrar) {
        final Set<HapiSpec> specs = new LinkedHashSet<>();
        return spec -> {
            if (specs.add(spec)) {
                registrar.registerWith(spec);
            }
        };
    }

    /**
     * Returns a list of all required entities for a call to a smart contract.
     *
     * @param target the target contract
     * @param parameters the parameters of the call
     * @return a list of all required entities
     */
    public static List<SpecEntity> allRequiredCallEntities(
            @NonNull final SpecEntity target, @NonNull final Object[] parameters) {
        List<SpecEntity> requiredEntities = new ArrayList<>();
        requiredEntities.add(target);
        for (final var parameter : parameters) {
            if (parameter instanceof SpecEntity entity) {
                requiredEntities.add(entity);
            }
        }
        return requiredEntities;
    }

    /**
     * Returns a contract id key activated by exclusively the given contract.
     *
     * @param contract the contract
     * @return a contract id key activated by exclusively the given contract
     */
    public static Key contractIdKeyFor(@NonNull final Account contract) {
        return Key.newBuilder()
                .contractID(ContractID.newBuilder()
                        .shardNum(contract.accountIdOrThrow().shardNum())
                        .realmNum(contract.accountIdOrThrow().realmNum())
                        .contractNum(contract.accountIdOrThrow().accountNumOrThrow())
                        .build())
                .build();
    }
}
