// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.keys;

import static com.hedera.services.yahcli.config.ConfigUtils.YAHCLI_LOGGING_CLASSES;
import static com.hedera.services.yahcli.output.CommonMessages.COMMON_MESSAGES;

import com.hedera.node.app.hapi.utils.keys.Ed25519Utils;
import com.hedera.node.app.hapi.utils.keys.Secp256k1Utils;
import com.hedera.services.bdd.spec.keys.deterministic.Bip0032;
import com.hedera.services.bdd.spec.utilops.inventory.AccessoryUtils;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.ECPrivateKey;
import java.util.concurrent.Callable;
import javax.crypto.ShortBufferException;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import org.hiero.base.utility.CommonUtils;
import picocli.CommandLine;

@CommandLine.Command(
        name = "print-keys",
        subcommands = {picocli.CommandLine.HelpCommand.class},
        description = "Prints the public and private keys in a Ed25519 key pair")
public class ExtractDetailsCommand implements Callable<Integer> {
    public static final String DER_EDDSA_PREFIX = "302e020100300506032b657004220420";

    // The length of a hex-encoded ECDSA private key is 64 characters, or 32 bytes
    private static final int ECDSA_HEX_KEY_LENGTH = 64;
    public static final String HEX_ZERO_PREFIX = "00";

    @CommandLine.ParentCommand
    private KeysCommand keysCommand;

    @CommandLine.Option(
            names = {"-p", "--path"},
            paramLabel = "path to PEM or mnemonic to print public/private keys",
            defaultValue = "keys/ed25519.pem")
    private String loc;

    @CommandLine.Option(
            names = {"-x", "--passphrase"},
            paramLabel = "passphrase for PEM (if needed and not present as matching .pass)")
    private String passphrase;

    @Override
    public Integer call() throws Exception {
        AccessoryUtils.setLogLevels(keysCommand.getYahcli().getLogLevel(), YAHCLI_LOGGING_CLASSES);

        KeyOutput output;
        if (loc.endsWith(".pem")) {
            if (passphrase == null) {
                final var passLoc = loc.replace(".pem", ".pass");
                passphrase = Files.readString(Paths.get(passLoc)).trim();
            }

            // Attempt to first read the key as an ED25519 key
            try {
                final var key = Ed25519Utils.readKeyFrom(new File(loc), passphrase);
                output = asEd25519Output(key);
            } catch (Exception e) {
                // If that doesn't work, try to read as a SECP256K1 key
                try {
                    final var key = Secp256k1Utils.readECKeyFrom(new File(loc), passphrase);
                    output = asSecp256k1Output(key);
                } catch (Exception e2) {
                    throw new IllegalArgumentException("Invalid key type");
                }
            }

        } else {
            final EdDSAPrivateKey edDSAPrivateKey = ed25519FromMnemonic(loc);
            output = asEd25519Output(edDSAPrivateKey);
        }

        COMMON_MESSAGES.info("The public key @ " + loc + " is : " + output.hexedPubKey);
        COMMON_MESSAGES.info("The private key @ " + loc + " is: " + output.hexedPrivKey);
        COMMON_MESSAGES.info("  -> With DER prefix; " + output.derEncoded);

        return 0;
    }

    private static KeyOutput asEd25519Output(final EdDSAPrivateKey key) {
        final var publicKey = Ed25519Utils.extractEd25519PublicKey(key);
        final var hexedPubKey = CommonUtils.hex(publicKey);
        final var hexedPrivKey = CommonUtils.hex(key.getSeed());
        final var derEncoded = CommonUtils.hex(key.getEncoded());
        return new KeyOutput(hexedPubKey, hexedPrivKey, derEncoded);
    }

    private static KeyOutput asSecp256k1Output(final ECPrivateKey privateKey) {
        final var publicKey = Secp256k1Utils.extractEcdsaPublicKey(privateKey);
        final var hexedPubKey = CommonUtils.hex(publicKey);

        String hexedPrivKey = CommonUtils.hex(privateKey.getS().toByteArray());
        // It's possible to compute a legitimate private ECDSA key with more than ECDSA_HEX_KEY_LENGTH bytes _if_ the
        // key has leading zeros. In such cases, strip out the leading zeros
        hexedPrivKey = stripPrefixPaddingBytes(hexedPrivKey);

        final var derEncoded = CommonUtils.hex(privateKey.getEncoded());

        return new KeyOutput(hexedPubKey, hexedPrivKey, derEncoded);
    }

    private static String stripPrefixPaddingBytes(final String hexKey) {
        String current = hexKey;
        while (current.length() > ECDSA_HEX_KEY_LENGTH && current.startsWith(HEX_ZERO_PREFIX)) {
            current = current.substring(2);
        }
        return current;
    }

    private static EdDSAPrivateKey ed25519FromMnemonic(final String wordsLoc)
            throws IOException, ShortBufferException, NoSuchAlgorithmException, InvalidKeyException {
        final var mnemonic = Files.readString(Paths.get(wordsLoc)).trim();
        final var seed = Bip0032.seedFrom(mnemonic);
        final var curvePoint = Bip0032.privateKeyFrom(seed);
        return Ed25519Utils.keyFrom(curvePoint);
    }

    private record KeyOutput(String hexedPubKey, String hexedPrivKey, String derEncoded) {}
}
