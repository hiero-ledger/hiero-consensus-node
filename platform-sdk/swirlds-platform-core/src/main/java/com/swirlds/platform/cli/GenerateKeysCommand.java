// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.cli;

import static com.swirlds.platform.crypto.CryptoStatic.generateKeysAndCerts;

import com.swirlds.cli.PlatformCli;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
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
import org.hiero.consensus.roster.RosterUtils;
import picocli.CommandLine;
import picocli.CommandLine.Parameters;

@CommandLine.Command(
        name = "generate-keys",
        mixinStandardHelpOptions = true,
        description = "Generates Node's X.509 certificate and private keys.")
@SubcommandOf(PlatformCli.class)
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
        var keysEntries = generateKeysAndCerts(ids.stream().map(NodeId::of).toList(), null);
        if (sigCertPath == null) {
            Files.createDirectories(Path.of(System.getProperty("user.dir")).resolve("data/keys"));
            sigCertPath = Path.of(System.getProperty("user.dir")).resolve("data/keys");
        }
        for (var kEntry : keysEntries.entrySet()) {
            var publicKeyStorePath =
                    sigCertPath.resolve(String.format("s-public-%s.pem", RosterUtils.formatNodeName(kEntry.getKey())));
            var privateKeyStorePath =
                    sigCertPath.resolve(String.format("s-private-%s.pem", RosterUtils.formatNodeName(kEntry.getKey())));
            EnhancedKeyStoreLoader.writePemFile(
                    true,
                    privateKeyStorePath,
                    kEntry.getValue().sigKeyPair().getPrivate().getEncoded());
            EnhancedKeyStoreLoader.writePemFile(
                    false, publicKeyStorePath, kEntry.getValue().sigCert().getEncoded());
        }
        CommonUtils.tellUserConsole("All " + ids.size() + " keys generated in:" + sigCertPath);
        return 0;
    }
}
