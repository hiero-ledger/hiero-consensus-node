// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.utils;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.infrastructure.HapiSpecRegistry;
import com.hedera.services.bdd.spec.keys.KeyRole;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hederahashgraph.api.proto.java.Key;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.PrivateKey;
import java.util.Map;

/**
 * Encapsulates metadata about a key, including its protoc and PBJ representations, a map of
 * hex-encoded public keys to their corresponding private keys, and the {@link SigControl}
 * instance that determines how constituent primitive keys should sign.
 *
 * @param protoKey the protoc representation of the key
 * @param pbjKey the PBJ representation of the key
 * @param privateKeyMap a map of hex-encoded public keys to their corresponding private keys
 * @param sigControl the signing control
 */
public record KeyMetadata(
        Key protoKey,
        com.hedera.hapi.node.base.Key pbjKey,
        Map<String, PrivateKey> privateKeyMap,
        SigControl sigControl,
        Registration registration) {

    public interface Registration {
        void save(HapiSpecRegistry registry, String name, Key key);
    }

    private static final Registration DEFAULT_REGISTRATION = HapiSpecRegistry::saveKey;

    public static Registration roleBasedRegistration(KeyRole role) {
        return (registry, name, key) -> registry.saveRoleKey(name, role, key);
    }
    /**
     * Constructs a {@link KeyMetadata} instance from the given protoc key and {@link HapiSpec}
     * with the default registration.
     *
     * @param protoKey the protoc key
     * @param spec the HapiSpec
     * @return the key metadata
     */
    public static KeyMetadata from(@NonNull final Key protoKey, @NonNull final HapiSpec spec) {
        return from(protoKey, spec, DEFAULT_REGISTRATION);
    }

    /**
     * Constructs a {@link KeyMetadata} instance from the given PBJ key and {@link HapiSpec}
     * with the default registration.
     *
     * @param pbjKey the protoc key
     * @param spec the HapiSpec
     * @return the key metadata
     */
    public static KeyMetadata from(@NonNull final com.hedera.hapi.node.base.Key pbjKey, @NonNull final HapiSpec spec) {
        requireNonNull(pbjKey);
        return from(null, pbjKey, spec, DEFAULT_REGISTRATION);
    }

    /**
     * Constructs a {@link KeyMetadata} instance from the given protoc key, {@link HapiSpec}, and
     * registration function.
     *
     * @param protoKey the protoc key
     * @param spec the HapiSpec
     * @param registration the registration function
     * @return the key metadata
     */
    public static KeyMetadata from(
            @NonNull final Key protoKey, @NonNull final HapiSpec spec, @NonNull final Registration registration) {
        requireNonNull(protoKey);
        return from(protoKey, null, spec, registration);
    }

    private static KeyMetadata from(
            @Nullable final Key maybeProtoKey,
            @Nullable final com.hedera.hapi.node.base.Key maybePbjKey,
            @NonNull final HapiSpec spec,
            @NonNull final Registration registration) {
        requireNonNull(spec);
        requireNonNull(registration);
        final var pbjKey = maybePbjKey != null ? maybePbjKey : toPbj(requireNonNull(maybeProtoKey));
        final var protoKey = maybeProtoKey != null ? maybeProtoKey : fromPbj(requireNonNull(maybePbjKey));
        return new KeyMetadata(
                protoKey,
                pbjKey,
                spec.keys().privateKeyMapFor(pbjKey),
                spec.keys().controlFor(protoKey),
                registration);
    }

    /**
     * Registers this key metadata under the given name in the given {@link HapiSpec}.
     *
     * @param name the name
     * @param spec the HapiSpec
     */
    public void registerAs(@NonNull final String name, @NonNull final HapiSpec spec) {
        requireNonNull(spec);
        requireNonNull(name);
        registration.save(spec.registry(), name, protoKey);
        spec.keys().setControl(protoKey, sigControl);
        spec.keys().addPrivateKeyMap(privateKeyMap);
    }
}
