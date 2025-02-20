// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.meta;

import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;

import com.hedera.node.app.hapi.utils.ByteStringUtils;
import com.hedera.node.app.hapi.utils.EthSigsUtils;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoCreate;
import com.hederahashgraph.api.proto.java.CryptoCreateTransactionBody;
import com.hederahashgraph.api.proto.java.Key;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.BiFunction;

/**
 * Represents a choice of the two account identifiers (key, alias) that can be used to
 * customize a {@link CryptoCreateTransactionBody}.
 *
 * <p>Helps the user by initializing a list of factories that, given an ECDSA key, will return one
 * of the 6 possible combinations of identifiers. (The key can only be present or not; but the
 * "secondary" alias identifier may be absent; present and congruent with the key; or
 * present and incongruent with the key.)
 *
 * @param key the ECDSA key to give as initial identifier (null if none)
 * @param alias the EVM address alias to give as initial identifier (null if none)
 */
@SuppressWarnings({"java:S6218", "java:S3358"})
public record InitialAccountIdentifiers(@Nullable Key key, @Nullable byte[] alias) {

    private static final int COMPRESSED_SECP256K1_PUBLIC_KEY_LEN = 33;
    public static final String KEY_FOR_INCONGRUENT_ALIAS = "keyForIncongruentAlias";

    private enum KeyStatus {
        ABSENT,
        PRESENT
    }

    private enum SecondaryIdStatus {
        ABSENT,
        CONGRUENT_WITH_KEY,
        INCONGRUENT_WITH_KEY
    }

    private static final List<BiFunction<HapiSpec, Key, InitialAccountIdentifiers>> ALL_COMBINATIONS = Arrays.stream(
                    KeyStatus.values())
            .flatMap(keyStatus -> Arrays.stream(SecondaryIdStatus.values())
                    .flatMap(aliasStatus -> Arrays.stream(SecondaryIdStatus.values())
                            .map(addressStatus -> fuzzerFor(keyStatus, aliasStatus))))
            .toList();

    private static final SplittableRandom RANDOM = new SplittableRandom();

    private static final int NUM_CHOICES = ALL_COMBINATIONS.size();

    public static InitialAccountIdentifiers fuzzedFrom(final HapiSpec spec, final Key key) {
        throwIfNotEcdsa(key);
        return ALL_COMBINATIONS.get(RANDOM.nextInt(NUM_CHOICES)).apply(spec, key);
    }

    public void customize(final HapiCryptoCreate op, final CryptoCreateTransactionBody.Builder opBody) {
        if (key != null) {
            opBody.setKey(key);
        }
        if (alias != null) {
            opBody.setAlias(ByteStringUtils.wrapUnsafely(alias));
        }

        opBody.setReceiverSigRequired(RANDOM.nextBoolean());

        final var shouldIncludeSigForIncongruentAlias = RANDOM.nextBoolean();
        if (shouldIncludeSigForIncongruentAlias) {
            op.signedBy(GENESIS, KEY_FOR_INCONGRUENT_ALIAS);
            op.sigMapPrefixes(uniqueWithFullPrefixesFor(KEY_FOR_INCONGRUENT_ALIAS));
        }
    }

    private static BiFunction<HapiSpec, Key, InitialAccountIdentifiers> fuzzerFor(
            final KeyStatus keyStatus, final SecondaryIdStatus aliasStatus) {
        return (spec, key) -> {
            final var accountKey = keyStatus == KeyStatus.ABSENT ? null : key;
            byte[] accountAlias = null;
            if (aliasStatus == SecondaryIdStatus.CONGRUENT_WITH_KEY) {
                final var keyBytes = key.getECDSASecp256K1().toByteArray();
                if (keyBytes.length != COMPRESSED_SECP256K1_PUBLIC_KEY_LEN) {
                    throw new IllegalArgumentException("Invalid key bytes length");
                }

                accountAlias = EthSigsUtils.recoverAddressFromPubKey(keyBytes);
            } else if (aliasStatus == SecondaryIdStatus.INCONGRUENT_WITH_KEY) {
                final var keyBytesForIncongruentAlias = spec.registry()
                        .getKey(KEY_FOR_INCONGRUENT_ALIAS)
                        .getECDSASecp256K1()
                        .toByteArray();

                accountAlias = EthSigsUtils.recoverAddressFromPubKey(keyBytesForIncongruentAlias);
            }

            return new InitialAccountIdentifiers(accountKey, accountAlias);
        };
    }

    public static void throwIfNotEcdsa(final Key key) {
        if (!key.hasECDSASecp256K1()) {
            throw new IllegalArgumentException("Key must be an ECDSA key to imply an address");
        }
    }
}
