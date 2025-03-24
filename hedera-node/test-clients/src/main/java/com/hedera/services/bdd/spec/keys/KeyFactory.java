// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.keys;

import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.keys.SigMapGenerator.Nature.UNIQUE_PREFIXES;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asContractId;
import static java.util.Map.Entry;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.node.app.hapi.utils.SignatureGenerator;
import com.hedera.node.app.hapi.utils.keys.KeyUtils;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.infrastructure.HapiSpecRegistry;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.ThresholdKey;
import com.hederahashgraph.api.proto.java.Transaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.IntStream;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import org.apache.commons.lang3.tuple.Pair;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.junit.jupiter.api.Assertions;

/**
 * A factory for generating keys, including complex key structures, and signing transactions with them
 * in the context of a {@link HapiSpec}.
 *
 * <p>For each cryptographic key generated, the factory maintains a map from its hexed public key to a
 * {@link PrivateKey} instance that can be used for signing.
 *
 * <p>For each key structure generated, the factory maintains a map from the key to the {@link SigControl}
 * instance that determines how that key will sign; for example, whether a given primitive key in the
 * structure should sign or not.
 *
 * <p>Also has methods to export named Ed25519 and ECDSA(secp256k1) keys to files.
 */
public class KeyFactory {
    /**
     * The default passphrase to use when exporting
     */
    public static String PEM_PASSPHRASE = "swirlds";

    /**
     * For convenience, enumerates the fundamental key structures.
     */
    public enum KeyType {
        SIMPLE,
        LIST,
        THRESHOLD
    }

    /**
     * A map from hexed public keys to corresponding private keys.
     */
    private final Map<String, PrivateKey> pkMap = new ConcurrentHashMap<>();
    /**
     * A map from keys to their corresponding {@link SigControl} instances.
     */
    private final Map<Key, SigControl> controlMap = new ConcurrentHashMap<>();
    /**
     * The default {@link SigMapGenerator}, uses unique prefixes for all signatures in the {@link SignatureMap}.
     */
    private final SigMapGenerator defaultSigMapGen = TrieSigMapGenerator.withNature(UNIQUE_PREFIXES);
    /**
     * The {@link HapiSpecSetup} to use to customize key generation.
     */
    private final HapiSpecSetup setup;
    /**
     * The {@link HapiSpecRegistry} to use to get key structures by their spec names.
     */
    private final HapiSpecRegistry registry;

    public KeyFactory(@NonNull final HapiSpecSetup setup, @NonNull final HapiSpecRegistry registry) throws Exception {
        this.setup = requireNonNull(setup);
        this.registry = requireNonNull(registry);
        final var genesisKey = setup.payerKeyAsEd25519();
        incorporate(setup.genesisAccountName(), genesisKey, KeyShape.listSigs(ON));
    }

    /**
     * Given a potentially complex key structure, returns a map of its constituent hexed public
     * keys to their corresponding private keys.
     *
     * @param key the key structure to traverse, mapping hexed public keys to private keys
     * @return the map of hexed public keys to private keys
     */
    public Map<String, PrivateKey> privateKeyMapFor(@NonNull final com.hedera.hapi.node.base.Key key) {
        requireNonNull(key);
        final Map<String, PrivateKey> keyMap = new HashMap<>();
        addPrivateKeys(key, keyMap);
        return keyMap;
    }

    /**
     * Adds the given private key map to the factory's internal map of hexed public keys to private keys.
     *
     * @param keyMap the map of hexed public keys to private keys
     */
    public void addPrivateKeyMap(@NonNull final Map<String, PrivateKey> keyMap) {
        requireNonNull(keyMap);
        pkMap.putAll(keyMap);
    }

    /**
     * Returns the ECDSA private key associated with the given hexed public key.
     *
     * @param pubKeyHex the hexed public key
     * @return the ECDSA private key
     */
    public ECPrivateKey getEcdsaPrivateKey(@NonNull final String pubKeyHex) {
        return (ECPrivateKey) pkMap.get(pubKeyHex);
    }

    /**
     * Returns the ECDSA private key associated with the given hexed public key.
     *
     * @param pubKeyHex the hexed public key
     * @return the ECDSA private key
     */
    public PrivateKey getEd25519PrivateKey(@NonNull final String pubKeyHex) {
        return pkMap.get(pubKeyHex);
    }

    /**
     * Exports the Ed25519 private key associated with the given name to the given PEM location
     * using the default passphrase.
     *
     * @param loc the location to which the key should be exported
     * @param name the name of the key to export
     */
    public void exportEd25519Key(@NonNull final String loc, @NonNull final String name) {
        exportEd25519Key(loc, name, key -> key.getEd25519().toByteArray());
    }

    /**
     * Exports the Ed25519 private key associated with the given name to the given PEM location
     * using the given passphrase.
     *
     * @param loc the location to which the key should be exported
     * @param name the name of the key to export
     * @param passphrase the passphrase to use for the PEM file
     */
    public void exportEd25519Key(
            @NonNull final String loc, @NonNull final String name, @NonNull final String passphrase) {
        exportEd25519Key(loc, name, key -> key.getEd25519().toByteArray(), passphrase);
    }

    /**
     * Exports the first Ed25519 key in the key list associated with the given name to the given PEM
     * location using the default passphrase.
     *
     * @param loc the location to which the key should be exported
     * @param name the name of the key to export
     */
    public void exportFirstEd25519FromKeyList(@NonNull final String loc, @NonNull final String name) {
        exportEd25519Key(
                loc, name, key -> key.getKeyList().getKeys(0).getEd25519().toByteArray());
    }

    /**
     * Exports the ECDSA private key associated with the given name to the given location in a plaintext format.
     *
     * @param name the name of the key to export
     */
    public void exportEcdsaKey(@NonNull final String name, @NonNull final String loc, @NonNull final String pass) {
        exportEcdsaKey(name, loc, pass, key -> key.getECDSASecp256K1().toByteArray());
    }

    /**
     * Returns the location the named ECDSA key would be stored by default if exported from
     * this key factory.
     *
     * @param name the name of the key
     * @return the default location for the key
     */
    public static String explicitEcdsaLocFor(@NonNull final String name) {
        return name + "-secp256k1.hexed";
    }

    /**
     * Allow inclusion of another key factory from the shared state
     *
     * @param sharedStateKeyFactory key factory from shared state
     */
    public void include(KeyFactory sharedStateKeyFactory) {
        pkMap.putAll(sharedStateKeyFactory.pkMap);
        controlMap.putAll(sharedStateKeyFactory.controlMap);
    }

    /**
     * Incorporates the named Ed25519 private key, which must already exist in the {@link HapiSpecRegistry},
     * into the factory's internal map of hexed public keys to private keys along with a {@link SigControl}
     * mapping that assumes the key will always sign a transaction when requested.
     *
     * <p><b>(FUTURE)</b> Replace with this with a method that also takes care of adding the key to the registry.
     *
     * @param byName the name of the key
     * @param key the private key to incorporate
     */
    public void incorporate(@NonNull final String byName, @NonNull final EdDSAPrivateKey key) {
        incorporate(byName, key, ON);
    }

    /**
     * Incorporates the named Ed25519 private key, which must already exist in the {@link HapiSpecRegistry},
     * into the factory's internal map of hexed public keys to private keys along with the given {@link SigControl}.
     *
     * <p><b>(FUTURE)</b> Replace with this with a method that also takes care of adding the key to the registry.
     *
     * @param name the name of the key
     * @param key the private key to incorporate
     * @param control the signing control for the key
     */
    public void incorporate(
            @NonNull final String name, @NonNull final EdDSAPrivateKey key, @NonNull final SigControl control) {
        final var pubKeyHex = org.hiero.consensus.model.utility.CommonUtils.hex(key.getAbyte());
        pkMap.put(pubKeyHex, key);
        controlMap.put(registry.getKey(name), control);
    }

    /**
     * Incorporates the named ECDSA private key, which must already exist in the {@link HapiSpecRegistry}, into
     * the factory's internal map of hexed public keys to private keys along with a {@link SigControl} mapping
     * that assumes the key will always sign a transaction as part of a size-one key list.
     *
     * <p><b>(FUTURE)</b> Replace with this with a method that also takes care of adding the key to the registry.
     *
     * @param name the name of the key
     * @param key the private key to incorporate
     */
    public void incorporateEd25519SimpleWacl(@NonNull final String name, @NonNull final EdDSAPrivateKey key) {
        final var pubKeyHex = org.hiero.consensus.model.utility.CommonUtils.hex(key.getAbyte());
        incorporate(name, pubKeyHex, key, KeyShape.listOf(1));
    }

    /**
     * Incorporates the named private key, which must already exist in the {@link HapiSpecRegistry}, into the
     * factory's internal map of hexed public keys to private keys along with the given {@link SigControl}.
     *
     * @param name the name of the key in the registry
     * @param pubKeyHex the hexed public key
     * @param privateKey the private key to incorporate
     * @param control the signing control for the key
     */
    public void incorporate(
            @NonNull final String name,
            @NonNull final String pubKeyHex,
            @NonNull final PrivateKey privateKey,
            @NonNull final SigControl control) {
        pkMap.put(pubKeyHex, privateKey);
        controlMap.put(registry.getKey(name), control);
    }

    /**
     * Returns the {@link SigControl} instance that determines how the given key should sign.
     *
     * @param key the key
     * @return the sig control for the key
     */
    public SigControl controlFor(@NonNull final Key key) {
        return controlMap.get(key);
    }

    /**
     * Sets the {@link SigControl} instance that determines how the given key should sign.
     *
     * @param key the key
     * @param control the sig control for the key
     */
    public void setControl(@NonNull final Key key, @NonNull final SigControl control) {
        controlMap.put(key, control);
    }

    /**
     * Returns the number of simple keys that will sign in the given key structure when using the
     * given {@link SigControl} overrides; or the default {@link SigControl} if the key does not have
     * an override.
     *
     * @param key the key structure
     * @param overrides the sig control overrides
     * @return the number of simple keys that will sign
     */
    public int controlledKeyCount(@NonNull final Key key, @NonNull final Map<Key, SigControl> overrides) {
        return asAuthor(key, overrides).getValue().numSimpleKeys();
    }

    /**
     * Signs the given transaction with the given keys, using the given {@link SigControl} overrides
     * to determine which keys should sign and the default {@link SigMapGenerator} to generate the
     * {@link SignatureMap} for the transaction.
     *
     * @param spec the active spec if needed to look up full prefix keys
     * @param txn the transaction to sign
     * @param keys the keys that should sign
     * @param overrides the sig control overrides
     * @return the signed transaction
     * @throws Throwable if signing fails
     */
    public Transaction sign(
            @Nullable final HapiSpec spec,
            @NonNull final Transaction.Builder txn,
            @NonNull final List<Key> keys,
            @NonNull final Map<Key, SigControl> overrides)
            throws Throwable {
        return sign(spec, txn, defaultSigMapGen, authorsFor(keys, overrides));
    }

    /**
     * Signs the given transaction with the given keys, using the given {@link SigControl} overrides
     * to determine which keys should sign and the given {@link SigMapGenerator} to generate the
     * {@link SignatureMap} for the transaction.
     *
     * @param spec the active spec if needed to look up full prefix keys
     * @param txn the transaction to sign
     * @param keys the keys that should sign
     * @param overrides the sig control overrides
     * @param sigMapGen the sig map generator
     * @return the signed transaction
     * @throws Throwable if signing fails
     */
    public Transaction sign(
            @Nullable final HapiSpec spec,
            @NonNull final Transaction.Builder txn,
            @NonNull final List<Key> keys,
            @NonNull final Map<Key, SigControl> overrides,
            @NonNull final SigMapGenerator sigMapGen)
            throws Throwable {
        return sign(spec, txn, sigMapGen, authorsFor(keys, overrides));
    }
    /**
     * Generates a key in the context of the given spec, using the default key generator.
     *
     * @param spec the active spec
     * @param type the key type to generate
     * @return the generated key
     */
    public Key generate(@NonNull final HapiSpec spec, @NonNull final KeyType type) {
        return generate(spec, type, spec.keyGenerator());
    }

    /**
     * Generates a key in the context of the given spec, using the given key generator.
     *
     * @param spec the active spec
     * @param type the key type to generate
     * @param keyGen the key generator
     * @return the generated key
     */
    public Key generate(@NonNull final HapiSpec spec, @NonNull final KeyType type, @NonNull final KeyGenerator keyGen) {
        return switch (type) {
            case THRESHOLD -> generateSubjectTo(
                    spec,
                    KeyShape.threshSigs(
                            setup.defaultThresholdM(),
                            IntStream.range(0, setup.defaultThresholdN())
                                    .mapToObj(ignore -> ON)
                                    .toArray(SigControl[]::new)),
                    keyGen);
            case LIST -> generateSubjectTo(
                    spec,
                    KeyShape.listSigs(IntStream.range(0, setup.defaultListN())
                            .mapToObj(ignore -> ON)
                            .toArray(SigControl[]::new)),
                    keyGen);
            default -> generateSubjectTo(spec, ON, keyGen);
        };
    }

    /**
     * Generates a key structure shaped to match the given {@link SigControl} in the context
     * of the given spec, including using the spec's default key generator.
     *
     * @param spec the active spec
     * @param sigControl the sig control whose shape to match
     * @return the generated key
     */
    public synchronized Key generateSubjectTo(@NonNull final HapiSpec spec, @NonNull final SigControl sigControl) {
        return new Generation(spec, sigControl, spec.keyGenerator()).outcome();
    }

    /**
     * Generates a key structure shaped to match the given {@link SigControl} using the given
     * {@link KeyGenerator} in the context of the given spec.
     *
     * @param spec the active spec
     * @param sigControl the sig control whose shape to match
     * @param keyGen the key generator
     * @return the generated key
     */
    public synchronized Key generateSubjectTo(
            @NonNull final HapiSpec spec, @NonNull final SigControl sigControl, @NonNull final KeyGenerator keyGen) {
        return new Generation(spec, sigControl, keyGen).outcome();
    }

    /**
     * Generates a key structure shaped to match the given {@link SigControl} using the given
     * {@link KeyGenerator} in the context of the given spec, using the given {@link KeyLabels}
     * to repeat primitive keys in the structure as requested.
     *
     * @param spec the active spec
     * @param controller the sig control whose shape to match
     * @param keyGen the key generator
     * @param labels the key labels to use
     * @return the generated key
     */
    public synchronized Key generateSubjectTo(
            @NonNull final HapiSpec spec,
            @NonNull final SigControl controller,
            @NonNull final KeyGenerator keyGen,
            @NonNull final KeyLabels labels) {
        return new Generation(spec, controller, keyGen, labels).outcome();
    }

    private class Generation {
        private final SigControl.KeyAlgo[] algoChoices = {SigControl.KeyAlgo.ED25519, SigControl.KeyAlgo.SECP256K1};

        private final KeyLabels labels;
        private final SigControl control;
        private final HapiSpec spec;
        private final KeyGenerator keyGen;
        private final Map<String, Key> byLabel = new HashMap<>();

        private int nextUnspecifiedAlgo = 0;

        private Generation(final HapiSpec spec, final SigControl control, final KeyGenerator keyGen) {
            this(spec, control, keyGen, KeyLabels.uniquelyLabeling(control));
        }

        private Generation(
                final HapiSpec spec, final SigControl control, final KeyGenerator keyGen, final KeyLabels labels) {
            this.spec = spec;
            this.labels = labels;
            this.control = control;
            this.keyGen = keyGen;
        }

        private Key outcome() {
            return generate(control, labels, true);
        }

        private Key generate(final SigControl sc, final KeyLabels label, final boolean saving) {
            final Key generated =
                    switch (sc.getNature()) {
                        case PREDEFINED -> registry.getKey(sc.predefined());
                        case CONTRACT_ID -> {
                            final var cid = asContractId(sc.contract(), spec);
                            yield Key.newBuilder().setContractID(cid).build();
                        }
                        case DELEGATABLE_CONTRACT_ID -> {
                            final var dcid = asContractId(sc.delegatableContract(), spec);
                            yield Key.newBuilder()
                                    .setDelegatableContractId(dcid)
                                    .build();
                        }
                        case LIST -> Key.newBuilder()
                                .setKeyList(composing(label.getConstituents(), sc.getChildControls()))
                                .build();
                        case THRESHOLD -> {
                            final var tKey = ThresholdKey.newBuilder()
                                    .setThreshold(sc.getThreshold())
                                    .setKeys(composing(label.getConstituents(), sc.getChildControls()))
                                    .build();
                            yield Key.newBuilder().setThresholdKey(tKey).build();
                        }
                        case SIG_ON, SIG_OFF -> {
                            if (byLabel.containsKey(label.literally())) {
                                yield byLabel.get(label.literally());
                            } else {
                                final SigControl.KeyAlgo choice;
                                if (sc.keyAlgo() == SigControl.KeyAlgo.UNSPECIFIED) {
                                    final var defaultAlgo = spec.setup().defaultKeyAlgo();
                                    if (defaultAlgo != SigControl.KeyAlgo.UNSPECIFIED) {
                                        choice = defaultAlgo;
                                    } else {
                                        /* A spec run with unspecified default algorithm alternates between Ed25519 and ECDSA */
                                        choice = algoChoices[nextUnspecifiedAlgo];
                                        nextUnspecifiedAlgo = (nextUnspecifiedAlgo + 1) % algoChoices.length;
                                    }
                                } else {
                                    choice = sc.keyAlgo();
                                }
                                final var generation = generateByAlgo(choice);
                                byLabel.put(label.literally(), generation);
                                yield generation;
                            }
                        }
                    };
            if (saving) {
                controlMap.put(generated, sc);
            }
            return generated;
        }

        private Key generateByAlgo(final SigControl.KeyAlgo algo) {
            if (algo == SigControl.KeyAlgo.ED25519) {
                return keyGen.genEd25519AndUpdateMap(pkMap);
            } else if (algo == SigControl.KeyAlgo.SECP256K1) {
                return keyGen.genEcdsaSecp256k1AndUpdate(pkMap);
            } else {
                throw new IllegalArgumentException(algo + " not supported");
            }
        }

        private KeyList composing(final KeyLabels[] ls, final SigControl[] cs) {
            Assertions.assertEquals(ls.length, cs.length, "Incompatible ls and cs!");
            final int N = ls.length;
            return KeyList.newBuilder()
                    .addAllKeys(IntStream.range(0, N)
                            .mapToObj(i -> generate(cs[i], ls[i], false))
                            .collect(toList()))
                    .build();
        }
    }

    private void addPrivateKeys(
            @NonNull final com.hedera.hapi.node.base.Key key, @NonNull final Map<String, PrivateKey> keyMap) {
        switch (key.key().kind()) {
            case ED25519 -> {
                final var hexedPubKey = org.hiero.consensus.model.utility.CommonUtils.hex(
                        key.ed25519OrThrow().toByteArray());
                keyMap.put(hexedPubKey, requireNonNull(pkMap.get(hexedPubKey)));
            }
            case ECDSA_SECP256K1 -> {
                final var hexedPubKey = org.hiero.consensus.model.utility.CommonUtils.hex(
                        key.ecdsaSecp256k1OrThrow().toByteArray());
                keyMap.put(hexedPubKey, requireNonNull(pkMap.get(hexedPubKey)));
            }
            case KEY_LIST -> key.keyListOrThrow().keys().forEach(k -> addPrivateKeys(k, keyMap));
            case THRESHOLD_KEY -> key.thresholdKeyOrThrow()
                    .keysOrThrow()
                    .keys()
                    .forEach(k -> addPrivateKeys(k, keyMap));
            default -> {
                // No-op
            }
        }
    }

    private void exportEd25519Key(
            @NonNull final String loc,
            @NonNull final String name,
            @NonNull final Function<Key, byte[]> targetKeyExtractor) {
        exportEd25519Key(loc, name, targetKeyExtractor, PEM_PASSPHRASE);
    }

    private void exportEd25519Key(
            @NonNull final String loc,
            @NonNull final String name,
            @NonNull final Function<Key, byte[]> targetKeyExtractor,
            @NonNull final String passphrase) {
        final var pubKeyBytes = targetKeyExtractor.apply(registry.getKey(name));
        final var hexedPubKey = org.hiero.consensus.model.utility.CommonUtils.hex(pubKeyBytes);
        final var key = (EdDSAPrivateKey) pkMap.get(hexedPubKey);
        KeyUtils.writeKeyTo(key, loc, passphrase);
    }

    private void exportEcdsaKey(
            @NonNull final String name,
            @NonNull final String loc,
            @NonNull final String pass,
            @NonNull final Function<Key, byte[]> targetKeyExtractor) {
        final var pubKeyBytes = targetKeyExtractor.apply(registry.getKey(name));
        final var hexedPubKey = org.hiero.consensus.model.utility.CommonUtils.hex(pubKeyBytes);
        final var key = (ECPrivateKey) pkMap.get(hexedPubKey);
        final var explicitLoc = loc != null ? loc : explicitEcdsaLocFor(name);
        KeyUtils.writeKeyTo(key, explicitLoc, pass);
    }

    private List<Entry<Key, SigControl>> authorsFor(
            @NonNull final List<Key> keys, @NonNull final Map<Key, SigControl> overrides) {
        return keys.stream().map(k -> asAuthor(k, overrides)).collect(toList());
    }

    private Entry<Key, SigControl> asAuthor(@NonNull final Key key, @NonNull final Map<Key, SigControl> overrides) {
        final SigControl control = overrides.getOrDefault(key, controlMap.get(key));

        if (control == null) {
            throw new IllegalArgumentException("No sig control for key " + key);
        }
        if (!control.appliesTo(key)) {
            throw new IllegalStateException("Control " + control + " for key " + key + " doesn't apply");
        }

        return Pair.of(key, control);
    }

    private Transaction sign(
            @Nullable final HapiSpec spec,
            @NonNull final Transaction.Builder txn,
            @NonNull final SigMapGenerator sigMapGen,
            @NonNull final List<Entry<Key, SigControl>> authors)
            throws Throwable {
        final var signing = new PrimitiveSigning(CommonUtils.extractTransactionBodyBytes(txn), authors);
        final var primitiveSigs = signing.completed();
        final var sigMap = sigMapGen.forPrimitiveSigs(spec, primitiveSigs);
        txn.setSigMap(sigMap);
        return txn.build();
    }

    private class PrimitiveSigning {
        private byte[] keccak256Digest;
        private final byte[] data;
        private final Set<String> used = new HashSet<>();
        private final List<Entry<Key, SigControl>> authors;
        private final List<Entry<byte[], byte[]>> keySigs = new ArrayList<>();

        private PrimitiveSigning(final byte[] data, final List<Entry<Key, SigControl>> authors) {
            this.data = data;
            this.authors = authors;
        }

        private List<Entry<byte[], byte[]>> completed() throws GeneralSecurityException {
            for (final var author : authors) {
                signRecursively(author.getKey(), author.getValue());
            }
            return keySigs;
        }

        private void signRecursively(final Key key, final SigControl controller) throws GeneralSecurityException {
            switch (controller.getNature()) {
                case SIG_OFF:
                    keySigs.add(new AbstractMap.SimpleEntry<>(extractPubKey(key), new byte[0]));
                    break;
                case CONTRACT_ID:
                case DELEGATABLE_CONTRACT_ID:
                    break;
                case SIG_ON:
                    signIfNecessary(key);
                    break;
                default:
                    final KeyList composite = TxnUtils.getCompositeList(key);
                    final SigControl[] childControls = controller.getChildControls();
                    for (int i = 0; i < childControls.length; i++) {
                        signRecursively(composite.getKeys(i), childControls[i]);
                    }
            }
        }

        private void signIfNecessary(final Key key) throws GeneralSecurityException {
            final var pk = extractPubKey(key);
            final var hexedPk = org.hiero.consensus.model.utility.CommonUtils.hex(pk);
            if (!used.contains(hexedPk)) {
                final var privateKey = pkMap.get(hexedPk);
                final byte[] sig;
                if (privateKey instanceof ECPrivateKey) {
                    if (keccak256Digest == null) {
                        keccak256Digest = new Keccak.Digest256().digest(data);
                    }
                    sig = SignatureGenerator.signBytes(keccak256Digest, privateKey);
                } else {
                    sig = SignatureGenerator.signBytes(data, privateKey);
                }
                keySigs.add(new AbstractMap.SimpleEntry<>(pk, sig));
                used.add(hexedPk);
            }
        }

        private byte[] extractPubKey(final Key key) {
            if (!key.getECDSASecp256K1().isEmpty()) {
                return key.getECDSASecp256K1().toByteArray();
            } else if (!key.getEd25519().isEmpty()) {
                return key.getEd25519().toByteArray();
            } else {
                throw new IllegalArgumentException("No supported public key in " + key);
            }
        }
    }
}
