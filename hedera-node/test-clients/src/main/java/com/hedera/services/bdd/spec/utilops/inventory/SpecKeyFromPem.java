// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.inventory;

import static com.hedera.services.bdd.spec.utilops.inventory.SpecKeyFromEcdsaFile.createAndLinkEcdsaKey;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.keys.Ed25519Utils;
import com.hedera.node.app.hapi.utils.keys.KeyUtils;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import java.io.File;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.util.Optional;
import java.util.function.Supplier;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;

public class SpecKeyFromPem extends UtilOp {
    private static final String DEFAULT_PASSPHRASE = "swirlds";

    private static final SigControl SIMPLE = SigControl.ON;
    private static final SigControl SIMPLE_WACL = KeyShape.listOf(1);

    private String pemLoc;
    private Optional<Supplier<String>> pemLocFn = Optional.empty();
    private String passphrase = DEFAULT_PASSPHRASE;
    private SigControl control = SIMPLE;
    private Optional<Supplier<String>> nameSupplier = Optional.empty();
    private Optional<Supplier<String>> linkSupplier = Optional.empty();
    private Optional<String> name = Optional.empty();
    private Optional<String> linkedId = Optional.empty();

    public SpecKeyFromPem(String pemLoc) {
        this.pemLoc = pemLoc;
    }

    public SpecKeyFromPem(Supplier<String> pemLocFn) {
        this.pemLocFn = Optional.of(pemLocFn);
    }

    public SpecKeyFromPem simpleWacl() {
        this.control = SIMPLE_WACL;
        return this;
    }

    public SpecKeyFromPem passphrase(String secret) {
        passphrase = secret;
        return this;
    }

    public SpecKeyFromPem name(String custom) {
        name = Optional.of(custom);
        return this;
    }

    public SpecKeyFromPem name(Supplier<String> nameFn) {
        nameSupplier = Optional.of(nameFn);
        return this;
    }

    public SpecKeyFromPem linkedTo(String id) {
        linkedId = Optional.of(id);
        return this;
    }

    public SpecKeyFromPem linkedTo(Supplier<String> linkFn) {
        linkSupplier = Optional.of(linkFn);
        return this;
    }

    private String actualName() {
        return nameSupplier.map(Supplier::get).orElse(name.orElse(pemLoc.substring(0, pemLoc.indexOf(".pem"))));
    }

    @Override
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        pemLoc = pemLocFn.map(Supplier::get).orElse(pemLoc);

        incorporateEd25519Pem(spec, control, pemLoc, passphrase, actualName(), linkedId, linkSupplier);

        return false;
    }

    static SigControl incorporateUnknownTypePem(
            final HapiSpec spec,
            final String pemLoc,
            final String passphrase,
            final String name,
            final Optional<String> linkedId,
            final Optional<Supplier<String>> linkSupplier) {
        final PrivateKey pk = KeyUtils.readUnknownTypeKeyFrom(new File(pemLoc), passphrase);
        final var control = TypedKey.from(pk).type();
        if (control == SigControl.SECP256K1_ON) {
            createAndLinkEcdsaKey(spec, (ECPrivateKey) pk, name, linkedId, linkSupplier, null);
        } else {
            incorporateEd25519Pem(spec, control, pemLoc, passphrase, name, linkedId, linkSupplier);
        }

        return control;
    }

    static void incorporateEd25519Pem(
            final HapiSpec spec,
            final SigControl control,
            final String pemLoc,
            final String passphrase,
            final String name,
            final Optional<String> linkedId,
            final Optional<Supplier<String>> linkSupplier) {
        final var seed = Ed25519Utils.readKeyFrom(pemLoc, passphrase);
        final var key = populatedFromSeed(control, seed);
        linkedId.ifPresent(s -> {
            spec.registry().saveAccountId(name, HapiPropertySource.asAccount(s));
            spec.registry().saveKey(s, key);
        });
        linkSupplier.ifPresent(fn -> {
            var s = fn.get();
            spec.registry().saveAccountId(name, HapiPropertySource.asAccount(s));
            spec.registry().saveKey(s, key);
        });
        spec.registry().saveKey(name, key);
        spec.keys().incorporate(name, seed, control);
    }

    private static Key populatedFromSeed(final SigControl control, final EdDSAPrivateKey key) {
        if (control == SIMPLE || control.getChildControls().length == 0) {
            return Key.newBuilder()
                    .setEd25519(ByteString.copyFrom(key.getAbyte()))
                    .build();
        } else if (control == SIMPLE_WACL) {
            return Key.newBuilder()
                    .setKeyList(KeyList.newBuilder()
                            .addKeys(Key.newBuilder().setEd25519(ByteString.copyFrom(key.getAbyte()))))
                    .build();
        } else {
            throw new IllegalStateException("Cannot populate key shape " + control);
        }
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        var helper = super.toStringHelper();
        helper.add("pem", pemLoc);
        name.ifPresent(n -> helper.add("name", n));
        return helper;
    }
}
