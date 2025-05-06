// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.inventory;

import static com.hedera.services.bdd.spec.keys.SigControl.ED25519_ON;
import static com.hedera.services.bdd.spec.utilops.inventory.AccessoryUtils.isValid;
import static com.hedera.services.bdd.spec.utilops.inventory.AccessoryUtils.keyFileAt;
import static com.hedera.services.bdd.spec.utilops.inventory.AccessoryUtils.passFileFor;
import static com.hedera.services.bdd.spec.utilops.inventory.AccessoryUtils.promptForPassphrase;
import static com.hedera.services.bdd.spec.utilops.inventory.NewSpecKey.exportEcdsaWithPass;
import static com.hedera.services.bdd.spec.utilops.inventory.NewSpecKey.exportEd25519WithPass;
import static com.hedera.services.bdd.spec.utilops.inventory.SpecKeyFromEcdsaFile.createAndLinkEcdsaKey;
import static com.hedera.services.bdd.spec.utilops.inventory.SpecKeyFromMnemonic.createAndLinkFromMnemonic;
import static com.hedera.services.bdd.spec.utilops.inventory.SpecKeyFromMnemonic.createAndLinkSimpleEdKey;
import static com.hedera.services.bdd.spec.utilops.inventory.SpecKeyFromPem.incorporateUnknownTypePem;

import com.google.common.base.MoreObjects;
import com.hedera.node.app.hapi.utils.keys.KeyUtils;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.keys.deterministic.Bip0032;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.Assertions;

public class SpecKeyFromFile extends UtilOp {
    private static final Logger log = LogManager.getLogger(SpecKeyFromFile.class);

    private final String name;
    private final String loc;
    private Optional<String> linkedId = Optional.empty();
    private Optional<String> immediateExportLoc = Optional.empty();
    private Optional<String> immediateExportPass = Optional.empty();

    public SpecKeyFromFile exportingTo(String loc, String pass) {
        immediateExportLoc = Optional.of(loc);
        immediateExportPass = Optional.of(pass);
        return this;
    }

    public SpecKeyFromFile yahcliLogged() {
        verboseLoggingOn = true;
        yahcliLogger = true;
        return this;
    }

    public SpecKeyFromFile(String name, String loc) {
        this.loc = loc;
        this.name = name;
    }

    public SpecKeyFromFile linkedTo(String id) {
        linkedId = Optional.of(id);
        return this;
    }

    @Override
    @SuppressWarnings({"java:S5960", "java:S3776"})
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        final var flexLoc = loc.substring(0, loc.lastIndexOf('.'));
        final var keyFile = keyFileAt(flexLoc);
        if (!keyFile.isPresent()) {
            throw new IllegalArgumentException("No key can be sourced from '" + loc + "'");
        }
        final var f = keyFile.orElseThrow();
        Optional<String> finalPassphrase = Optional.empty();
        final SigControl keyType;
        if (f.getName().endsWith(".pem")) {
            var optPassFile = passFileFor(f);
            if (optPassFile.isPresent()) {
                final var pf = optPassFile.get();
                try {
                    finalPassphrase = Optional.of(Files.readString(pf.toPath()).trim());
                } catch (IOException e) {
                    log.warn("Password file {} inaccessible for PEM {}", pf.getAbsolutePath(), name, e);
                }
            }
            if (!isValid(f, finalPassphrase)) {
                var prompt = "Please enter the passphrase for key file " + f.getName();
                finalPassphrase = promptForPassphrase(loc, prompt, 3);
            }
            if (finalPassphrase.isEmpty() || !isValid(f, finalPassphrase)) {
                Assertions.fail(String.format("No valid passphrase could be obtained for PEM %s", loc));
            }

            keyType = incorporateUnknownTypePem(
                    spec,
                    keyFile.get().getAbsolutePath(),
                    finalPassphrase.orElseThrow(),
                    name,
                    linkedId,
                    Optional.empty());
        } else if (f.getName().endsWith(".words")) {
            final var mnemonic = Bip0032.mnemonicFromFile(f.getAbsolutePath());
            createAndLinkFromMnemonic(spec, mnemonic, name, linkedId, null);
            keyType = ED25519_ON;
        } else {
            var hexed = Files.readString(f.toPath()).trim();
            final var privateKey = CommonUtils.unhex(hexed);
            keyType = createAndLinkSimpleKeyUnknownType(spec, privateKey, name, linkedId, null);
        }

        // Export the key (if requested)
        if (immediateExportLoc.isPresent() && immediateExportPass.isPresent()) {
            final var exportLoc = immediateExportLoc.get();
            final var exportPass = finalPassphrase.orElse(immediateExportPass.get());
            exportKeyWithPassByType(spec, name, exportLoc, exportPass, keyType);
            if (verboseLoggingOn && yahcliLogger) {
                System.out.println(".i. Exported key from " + flexLoc + " to " + exportLoc);
            }
        }
        return false;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        var helper = super.toStringHelper();
        helper.add("name", name);
        helper.add("loc", loc);
        linkedId.ifPresent(s -> helper.add("linkedId", s));
        return helper;
    }

    private static SigControl createAndLinkSimpleKeyUnknownType(
            HapiSpec spec,
            byte[] existingPrivateKeyBytes,
            String name,
            Optional<String> linkedId,
            @Nullable Logger logToUse) {
        final PrivateKey pk = KeyUtils.readUnknownTypeKeyFrom(existingPrivateKeyBytes);
        final var type = TypedKey.from(pk).type();
        if (type == SigControl.SECP256K1_ON) {
            createAndLinkEcdsaKey(spec, (ECPrivateKey) pk, name, linkedId, Optional.empty(), logToUse);
        } else {
            createAndLinkSimpleEdKey(spec, existingPrivateKeyBytes, name, linkedId, logToUse);
        }

        return type;
    }

    private static void exportKeyWithPassByType(
            HapiSpec spec, String name, String exportLoc, String exportPass, SigControl keyType) throws IOException {
        if (keyType == SigControl.SECP256K1_ON) {
            exportEcdsaWithPass(spec, name, exportLoc, exportPass);
        } else {
            exportEd25519WithPass(spec, name, exportLoc, exportPass);
        }
    }
}
