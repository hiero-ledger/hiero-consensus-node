// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli;

import static com.swirlds.platform.crypto.CryptoStatic.generateKeysAndCerts;

import com.swirlds.common.utility.CommonUtils;
import com.swirlds.platform.crypto.EnhancedKeyStoreLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStoreException;
import java.security.cert.CertificateEncodingException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.node.NodeUtilities;
import picocli.CommandLine;
import picocli.CommandLine.Parameters;

@CommandLine.Command(
        name = "generate-keys",
        mixinStandardHelpOptions = true,
        description = "Generates Node's X.509 certificate and private keys.")
@SubcommandOf(Pcli.class)
public class GenerateKeysCommand extends AbstractCommand {
    private Path sigCertPath;

    @Parameters
    private List<Integer> ids;

    /**
     * The path to state to edit
     */
    @CommandLine.Option(
            names = {"-p", "--path"},
            description = "Path to place the keys")
    private void setSigCertPath(final Path sigCertPath) {
        this.sigCertPath = pathMustExist(sigCertPath.toAbsolutePath());
    }

    @Override
    public Integer call()
            throws KeyStoreException, ExecutionException, InterruptedException, IOException,
                    CertificateEncodingException {
        var keysEntries = generateKeysAndCerts(ids.stream().map(NodeId::of).toList());
        if (sigCertPath == null) {
            Files.createDirectories(Path.of(System.getProperty("user.dir")).resolve("data/keys"));
            sigCertPath = Path.of(System.getProperty("user.dir")).resolve("data/keys");
        }
        for (var kEntry : keysEntries.entrySet()) {
            var nodeName = NodeUtilities.formatNodeName(kEntry.getKey());
            var keysAndCerts = kEntry.getValue();

            // Write RSA signing keys
            var publicKeyStorePath = sigCertPath.resolve(String.format("s-public-%s.pem", nodeName));
            var privateKeyStorePath = sigCertPath.resolve(String.format("s-private-%s.pem", nodeName));
            EnhancedKeyStoreLoader.writePemFile(
                    true,
                    privateKeyStorePath,
                    keysAndCerts.sigKeyPair().getPrivate().getEncoded());
            EnhancedKeyStoreLoader.writePemFile(
                    false, publicKeyStorePath, keysAndCerts.sigCert().getEncoded());

            // Write Ed25519 event signing keys if generated
            if (keysAndCerts.eventSigKeyPair() != null) {
                var eventPrivatePath = sigCertPath.resolve(String.format("e-private-%s.pem", nodeName));
                var eventPublicPath = sigCertPath.resolve(String.format("e-public-%s.pem", nodeName));
                EnhancedKeyStoreLoader.writePemFile(
                        true,
                        eventPrivatePath,
                        keysAndCerts.eventSigKeyPair().getPrivate().getEncoded());
                EnhancedKeyStoreLoader.writePemFile(
                        false,
                        eventPublicPath,
                        keysAndCerts.eventSigKeyPair().getPublic().getEncoded());
            }
        }
        CommonUtils.tellUserConsole("All " + ids.size() + " keys generated in:" + sigCertPath);
        return 0;
    }
}
