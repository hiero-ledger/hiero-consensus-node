// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.inventory;

import static com.hedera.services.bdd.spec.keys.KeyFactory.KeyType;
import static com.hedera.services.bdd.spec.keys.deterministic.Bip0039.randomMnemonic;
import static java.util.Objects.requireNonNull;
import static org.hiero.base.utility.CommonUtils.hex;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.keys.Ed25519Utils;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.keys.KeyFactory;
import com.hedera.services.bdd.spec.keys.KeyGenerator;
import com.hedera.services.bdd.spec.keys.KeyLabels;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.keys.deterministic.Bip0032;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.hederahashgraph.api.proto.java.Key;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Consumer;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class NewSpecKey extends UtilOp {
    static final Logger log = LogManager.getLogger(NewSpecKey.class);

    private boolean yahcliLogger = false;
    private boolean verboseLoggingOn = false;
    private boolean exportEd25519Mnemonic = false;
    private final String name;

    @Nullable
    private Consumer<Key> keyObserver;

    private Optional<String> immediateExportLoc = Optional.empty();
    private Optional<String> immediateExportPass = Optional.empty();
    private Optional<KeyType> type = Optional.empty();
    private Optional<SigControl> shape = Optional.empty();
    private Optional<KeyLabels> labels = Optional.empty();
    private Optional<KeyGenerator> generator = Optional.empty();

    public NewSpecKey(String name) {
        this.name = name;
    }

    public NewSpecKey exportingTo(String loc, String pass) {
        immediateExportLoc = Optional.of(loc);
        immediateExportPass = Optional.of(pass);
        return this;
    }

    public NewSpecKey exposingKeyTo(@NonNull final Consumer<Key> observer) {
        keyObserver = requireNonNull(observer);
        return this;
    }

    public NewSpecKey includingEd25519Mnemonic() {
        exportEd25519Mnemonic = true;
        return this;
    }

    public NewSpecKey logged() {
        verboseLoggingOn = true;
        return this;
    }

    public NewSpecKey yahcliLogged() {
        verboseLoggingOn = true;
        yahcliLogger = true;
        return this;
    }

    public NewSpecKey type(KeyType toGen) {
        type = Optional.of(toGen);
        return this;
    }

    public NewSpecKey shape(SigControl control) {
        shape = Optional.of(control);
        return this;
    }

    public NewSpecKey labels(KeyLabels kl) {
        labels = Optional.of(kl);
        return this;
    }

    public NewSpecKey generator(@NonNull final KeyGenerator gen) {
        generator = Optional.of(gen);
        return this;
    }

    @Override
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        if (exportEd25519Mnemonic) {
            if (!immediateExportLoc.isPresent() || !immediateExportPass.isPresent()) {
                throw new IllegalStateException("Must have an export location for the key info");
            }

            final var mnemonic = randomMnemonic();
            final var seed = Bip0032.seedFrom(mnemonic);
            final var curvePoint = Bip0032.privateKeyFrom(seed);
            final EdDSAPrivateKey privateKey = Ed25519Utils.keyFrom(curvePoint);

            final var pubKey = privateKey.getAbyte();
            final var key =
                    Key.newBuilder().setEd25519(ByteString.copyFrom(pubKey)).build();

            spec.registry().saveKey(name, key);
            spec.keys().incorporate(name, privateKey);

            final var exportLoc = immediateExportLoc.get();
            final var exportPass = immediateExportPass.get();
            // Note: we should never try to export any ECDSA key here, because ECDSA doesn't use mnemonics
            exportEd25519WithPass(spec, name, exportLoc, exportPass);
            final var wordsLoc = exportLoc.replace(".pem", ".words");
            Files.writeString(Paths.get(wordsLoc), mnemonic);
            return false;
        }

        final var keyGen = generator.orElse(spec.keyGenerator());
        Key key;
        if (shape.isPresent()) {
            if (labels.isPresent()) {
                key = spec.keys().generateSubjectTo(spec, shape.get(), keyGen, labels.get());
            } else {
                key = spec.keys().generateSubjectTo(spec, shape.get(), keyGen);
            }
        } else {
            key = spec.keys().generate(spec, type.orElse(KeyType.SIMPLE), keyGen);
        }
        spec.registry().saveKey(name, key);
        if (keyObserver != null) {
            keyObserver.accept(key);
        }
        if (immediateExportLoc.isPresent() && immediateExportPass.isPresent()) {
            final var exportLoc = immediateExportLoc.get();
            final var exportPass = immediateExportPass.get();
            if (shape.get() == SigControl.SECP256K1_ON) {
                exportEcdsaWithPass(spec, name, exportLoc, exportPass);
            } else {
                exportEd25519WithPass(spec, name, exportLoc, exportPass);
            }
            if (verboseLoggingOn && yahcliLogger) {
                System.out.println(".i. Exported a newly generated key in PEM format to " + exportLoc);
            }
        }
        if (verboseLoggingOn && !yahcliLogger) {
            if (type.orElse(KeyType.SIMPLE) == KeyType.SIMPLE) {
                log.info(
                        "Created simple '{}' w/ Ed25519 public key {}",
                        name,
                        hex(key.getEd25519().toByteArray()));
            } else {
                log.info("Created a complex key...");
            }
        }
        return false;
    }

    static void exportEcdsaWithPass(HapiSpec spec, String name, String exportLoc, String exportPass)
            throws IOException {
        exportWithPass(spec, name, exportLoc, exportPass, kf -> kf.exportEcdsaKey(name, exportLoc, exportPass));
    }

    static void exportEd25519WithPass(HapiSpec spec, String name, String exportLoc, String exportPass)
            throws IOException {
        exportWithPass(spec, name, exportLoc, exportPass, kf -> kf.exportEd25519Key(exportLoc, name, exportPass));
    }

    static void exportWithPass(
            HapiSpec spec, String name, String exportLoc, String exportPass, Consumer<KeyFactory> export)
            throws IOException {
        export.accept(spec.keys());
        final var passLoc = exportLoc.replace(".pem", ".pass");
        Files.writeString(Paths.get(passLoc), exportPass);
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("name", name);
    }
}
