// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl;

import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.infrastructure.HapiSpecRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * Encapsulates logic to manage a Hedera entity (e.g., account, file, or contract) in the
 * context of a {@link HapiSpec}.
 *
 * <p>Hides details of interacting with the {@link HapiSpecRegistry} from {@link HapiTest}
 * authors, who can simply inject POJO entities into their test classes and methods and
 * call methods on those objects to perform HAPI operations scoped to the managed entities.
 */
public interface SpecEntity {
    /**
     * Returns the {@link HapiSpecRegistry} name of this entity.
     *
     * @return the name of the entity
     */
    String name();

    /**
     * Returns the logic to register with a spec all information about this entity on a
     * given {@link HederaNetwork}, if it exists there.
     *
     * @param network the network to get the registrar for
     * @return the entity's registrar for the network, or {@code null} if it does not exist there
     */
    @Nullable
    SpecEntityRegistrar registrarFor(@NonNull HederaNetwork network);

    /**
     * If this entity is already created on the spec's target {@link HederaNetwork}, registers
     * its record (e.g., entity id, controlling key, and memo) with the given {@link HapiSpec}'s
     * registry.
     *
     * <p>If the entity is not already created on the network, blocks until it is created using
     * the given spec; then registers its record.
     *
     * @param spec the spec to use to create the entity if it is not already created
     */
    default void registerOrCreateWith(@NonNull final HapiSpec spec) {
        requireNonNull(spec);
        Optional.ofNullable(registrarFor(spec.targetNetworkOrThrow()))
                .orElseGet(() -> createWith(spec))
                .registerWith(spec);
    }

    /**
     * Given a list of entities, create them (if not already created) and register them all, in
     * the order listed.  That is, perform {@link SpecEntity#registerOrCreateWith} for each entity given, in order.
     *
     * <p>Useful to force entities to be created at a specific time and in a specific order, if you need
     * to predict the Hedera entity numbers of entities dynamically created during a test; e.g., by
     * smart contracts which themselves create contracts, or which use HTS.
     *
     * <p>Always use with <code>&#64;LeakyHapiTest(requirement = NO_CONCURRENT_CREATIONS)</code>.
     *
     * @param spec the spec to use to create an entity if it is not already created
     * @param entities - the entities to create, in order
     */
    static void forceCreateAndRegister(@NonNull final HapiSpec spec, final SpecEntity... entities) {
        requireNonNull(spec);
        for (final var entity : entities) {
            requireNonNull(entity).registerOrCreateWith(spec);
        }
    }

    /**
     * Creates this entity with the given {@link HapiSpec}, returning the registrar
     * for the spec's target network.
     *
     * @param spec the spec to use to create the entity
     */
    SpecEntityRegistrar createWith(@NonNull HapiSpec spec);

    /**
     * Locks the entity's model, preventing further modification.
     */
    void lock();

    /**
     * Returns a list of entities that must be created before this entity can be created.
     *
     * @return the prerequisite entities
     */
    default List<SpecEntity> prerequisiteEntities() {
        return emptyList();
    }
}
