// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.inventory;

import static com.hedera.node.app.hapi.utils.SignatureGenerator.BOUNCYCASTLE_PROVIDER;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.keys.Secp256k1Utils;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.hederahashgraph.api.proto.java.Key;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.util.Optional;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECPrivateKeySpec;
import org.hiero.consensus.model.utility.CommonUtils;

public class SpecKeyFromEcdsaFile extends UtilOp {
    private static final Logger log = LogManager.getLogger(SpecKeyFromEcdsaFile.class);

    private final String loc;
    private final String name;
    private final String hexedPubKey;
    private final BigInteger s;
    private Optional<String> linkedId = Optional.empty();

    public SpecKeyFromEcdsaFile(final String loc, final String name) {
        this.loc = com.hedera.services.bdd.spec.keys.KeyFactory.explicitEcdsaLocFor(loc);
        this.name = name;
        try {
            final var data = Files.readString(Paths.get(this.loc));
            final String[] parts = data.split("[|]");
            hexedPubKey = parts[0];
            s = new BigInteger(parts[1], 16);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ECPrivateKey ecdsaFrom(final BigInteger s) {
        final var params = ECNamedCurveTable.getParameterSpec("secp256k1");
        final var keySpec = new ECPrivateKeySpec(s, params);
        try {
            final KeyFactory kf = KeyFactory.getInstance("EC", BOUNCYCASTLE_PROVIDER);
            return (ECPrivateKey) kf.generatePrivate(keySpec);
        } catch (InvalidKeySpecException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void createAndLinkEcdsaKey(
            final HapiSpec spec,
            final ECPrivateKey privateKey,
            final String name,
            final Optional<String> linkedId,
            final Optional<Supplier<String>> linkSupplier,
            final @Nullable Logger logToUse) {
        final var pubKey = Secp256k1Utils.extractEcdsaPublicKey(privateKey);
        final var hexedPubKey = CommonUtils.hex(pubKey);
        if (logToUse != null) {
            logToUse.info("Hex-encoded public key: {}", hexedPubKey);
        }
        final var key =
                Key.newBuilder().setECDSASecp256K1(ByteString.copyFrom(pubKey)).build();
        spec.registry().saveKey(name, key);
        spec.keys().incorporate(name, hexedPubKey, privateKey, SigControl.SECP256K1_ON);
        linkedId.ifPresent(s -> spec.registry().saveAccountId(name, HapiPropertySource.asAccount(s)));
        linkSupplier.ifPresent(fn -> {
            var s = fn.get();
            spec.registry().saveAccountId(name, HapiPropertySource.asAccount(s));
            spec.registry().saveKey(s, key);
        });
    }

    public SpecKeyFromEcdsaFile linkedTo(final String id) {
        linkedId = Optional.of(id);
        return this;
    }

    @Override
    protected boolean submitOp(final HapiSpec spec) throws Throwable {
        final var privateKey = ecdsaFrom(s);
        createAndLinkEcdsaKey(spec, privateKey, name, linkedId, Optional.empty(), log);
        return false;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        final var helper = super.toStringHelper();
        helper.add("name", loc);
        linkedId.ifPresent(s -> helper.add("linkedId", s));
        return helper;
    }
}
