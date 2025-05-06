// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.inventory;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.deterministic.Bip0032;
import com.hedera.services.bdd.spec.keys.deterministic.Ed25519Factory;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import javax.crypto.ShortBufferException;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.utility.CommonUtils;

public class SpecKeyFromMnemonic extends UtilOp {
    private static final Logger log = LogManager.getLogger(SpecKeyFromMnemonic.class);

    private final String name;
    private final String mnemonic;
    private Optional<String> linkedId = Optional.empty();

    public SpecKeyFromMnemonic(String name, String mnemonic) {
        this.name = name;
        this.mnemonic = mnemonic;
    }

    public SpecKeyFromMnemonic linkedTo(String id) {
        linkedId = Optional.of(id);
        return this;
    }

    @Override
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        createAndLinkFromMnemonic(spec, mnemonic, name, linkedId, log);
        return false;
    }

    static void createAndLinkFromMnemonic(
            HapiSpec spec, String mnemonic, String name, Optional<String> linkedId, Logger logToUse)
            throws ShortBufferException, NoSuchAlgorithmException, InvalidKeyException {
        byte[] seed = Bip0032.seedFrom(mnemonic);
        byte[] privateKey = Bip0032.privateKeyFrom(seed);
        createAndLinkSimpleEdKey(spec, privateKey, name, linkedId, logToUse);
    }

    static void createAndLinkSimpleEdKey(
            HapiSpec spec, byte[] privateKey, String name, Optional<String> linkedId, @Nullable Logger logToUse) {
        var params = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        var privateKeySpec = new EdDSAPrivateKeySpec(privateKey, params);
        var pk = new EdDSAPrivateKey(privateKeySpec);
        var pubKeyHex = CommonUtils.hex(pk.getAbyte());
        if (logToUse != null) {
            logToUse.info("Hex-encoded public key: {}", pubKeyHex);
        }
        var key = Ed25519Factory.populatedFrom(pk.getAbyte());
        spec.registry().saveKey(name, key);
        spec.keys().incorporate(name, pubKeyHex, pk, KeyShape.SIMPLE);
        linkedId.ifPresent(s -> spec.registry().saveAccountId(name, HapiPropertySource.asAccount(s)));
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        var helper = super.toStringHelper();
        helper.add("name", name);
        linkedId.ifPresent(s -> helper.add("linkedId", s));
        return helper;
    }
}
