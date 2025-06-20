// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.entities;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.spec.dsl.utils.DslUtils.atMostOnce;
import static com.hedera.services.bdd.spec.keys.SigControl.ED25519_ON;
import static com.hedera.services.bdd.spec.keys.SigControl.SECP256K1_ON;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Key;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.dsl.utils.KeyMetadata;
import com.hedera.services.bdd.spec.utilops.NoOp;
import edu.umd.cs.findbugs.annotations.NonNull;

public class SpecKey extends AbstractSpecEntity<NoOp, Key> {
    public enum Type {
        ED25519,
        SECP_256K1
    }

    private final Type type;

    public SpecKey(@NonNull String name, @NonNull final Type type) {
        super(name);
        this.type = requireNonNull(type);
    }

    /**
     * Returns the raw bytes of the key for the given network.
     *
     * @param network the network
     * @return the raw bytes
     */
    public byte[] asEncodedOn(@NonNull final HederaNetwork network) {
        final var key = keyOrThrow(network);
        return switch (type) {
            case ED25519 -> key.ed25519OrThrow().toByteArray();
            case SECP_256K1 -> key.ecdsaSecp256k1OrThrow().toByteArray();
        };
    }

    /**
     * Gets the key model for the given network, or throws if it doesn't exist.
     *
     * @param network the network
     * @return the key model
     */
    public Key keyOrThrow(@NonNull final HederaNetwork network) {
        return modelOrThrow(network);
    }

    @Override
    protected Creation<NoOp, Key> newCreation(@NonNull final HapiSpec spec) {
        final var key = spec.keys()
                .generateSubjectTo(
                        spec,
                        switch (type) {
                            case ED25519 -> ED25519_ON;
                            case SECP_256K1 -> SECP256K1_ON;
                        });
        return new Creation<>(noOp(), toPbj(key));
    }

    @Override
    protected Result<Key> resultForSuccessful(
            @NonNull final Creation<NoOp, Key> creation, @NonNull final HapiSpec spec) {
        final var keyMetadata = KeyMetadata.from(creation.model(), spec);
        return new Result<>(keyMetadata.pbjKey(), atMostOnce(siblingSpec -> keyMetadata.registerAs(name, siblingSpec)));
    }
}
