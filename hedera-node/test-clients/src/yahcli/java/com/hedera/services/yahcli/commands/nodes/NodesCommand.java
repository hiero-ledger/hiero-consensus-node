// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.nodes;

import static com.hedera.services.bdd.spec.utilops.inventory.AccessoryUtils.promptForPassphrase;

import com.hedera.node.app.service.addressbook.AddressBookHelper;
import com.hedera.services.bdd.spec.utilops.inventory.AccessoryUtils;
import com.hedera.services.yahcli.Yahcli;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Optional;
import java.util.Spliterators;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.stream.StreamSupport;
import picocli.CommandLine;
import picocli.CommandLine.ParentCommand;

@CommandLine.Command(
        name = "nodes",
        subcommands = {UpdateCommand.class, CreateCommand.class, DeleteCommand.class},
        description = "Performs DAB nodes operations")
public class NodesCommand implements Callable<Integer> {
    @ParentCommand
    Yahcli yahcli;

    @Override
    public Integer call() throws Exception {
        throw new CommandLine.ParameterException(yahcli.getSpec().commandLine(), "Please specify a nodes subcommand");
    }

    public Yahcli getYahcli() {
        return yahcli;
    }

    /**
     * Given a location and a {@link Yahcli}, validates that a key file exists at the location or throws
     * a {@link picocli.CommandLine.ParameterException} with context on the command line that failed.
     *
     * @param loc the location to check for a key file
     * @param yahcli the {@link Yahcli} to use for context
     */
    static void validateKeyAt(@NonNull final String loc, @NonNull final Yahcli yahcli) {
        final Optional<File> keyFile;
        try {
            keyFile = AccessoryUtils.keyFileAt(loc.substring(0, loc.lastIndexOf('.')));
        } catch (Exception e) {
            throw new CommandLine.ParameterException(
                    yahcli.getSpec().commandLine(), "Could not load a key from '" + loc + "' (" + e.getMessage() + ")");
        }
        if (keyFile.isEmpty()) {
            throw new CommandLine.ParameterException(
                    yahcli.getSpec().commandLine(), "Could not load a key from '" + loc + "'");
        }
    }

    /**
     * Given a location and a {@link Yahcli}, validates that a X.509 certificate exists at the location and
     * returns its encoded bytes, or throws a {@link picocli.CommandLine.ParameterException} with context on the
     * command line that failed.
     *
     * @param certLoc if non-null, the location to check for a X.509 certificate
     * @param pfxLoc if non-null, the .pfx file to use for the certificate
     * @param pfxAlias if non-null, the alias in the .pfx file for the certificate
     * @param yahcli the {@link Yahcli} to use for context
     * @return the encoded bytes of the X.509 certificate
     */
    static byte[] validatedX509Cert(
            @Nullable final String certLoc,
            @Nullable final String pfxLoc,
            @Nullable final String pfxAlias,
            @NonNull final Yahcli yahcli) {
        if (certLoc != null) {
            try {
                return AddressBookHelper.readCertificatePemFile(Paths.get(certLoc))
                        .getEncoded();
            } catch (IOException | CertificateException e) {
                throw new CommandLine.ParameterException(
                        yahcli.getSpec().commandLine(), "Could not load a certificate from '" + certLoc + "'");
            }
        } else {
            if (pfxLoc == null) {
                throw new CommandLine.ParameterException(
                        yahcli.getSpec().commandLine(), "Either an X.509 cert or .pfx file must be given");
            }
            if (pfxAlias == null) {
                throw new CommandLine.ParameterException(
                        yahcli.getSpec().commandLine(), "An alias in the .pfx file must be given");
            }
            final var pfxFile = new File(pfxLoc);
            final var passFile = passFileFor(pfxFile);
            final String password;
            if (passFile.isPresent()) {
                try {
                    password = Files.readString(passFile.get().toPath()).trim();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                final var prompt = "Please enter the passphrase for .pfx file " + pfxFile.getName();
                final var maybePassword = promptForPassphrase(pfxLoc, prompt, 3, unlockTest(pfxAlias, yahcli));
                password = maybePassword.orElse("password");
            }
            return x509CertFromPfx(pfxLoc, password, pfxAlias, yahcli);
        }
    }

    private static BiPredicate<File, String> unlockTest(@NonNull final String alias, @NonNull final Yahcli yahcli) {
        return (keyFile, password) -> unlocksPfx(keyFile, password, alias, yahcli);
    }

    private static boolean unlocksPfx(
            @NonNull final File file,
            @NonNull final String passphrase,
            @NonNull final String alias,
            @NonNull final Yahcli yahcli) {
        try {
            x509CertFromPfx(file.getAbsolutePath(), passphrase, alias, yahcli);
            return true;
        } catch (Exception ignore) {
            return false;
        }
    }

    private static byte[] x509CertFromPfx(
            @NonNull final String loc,
            @NonNull final String password,
            @NonNull final String certAlias,
            @NonNull final Yahcli yahcli) {
        final AtomicReference<byte[]> certBytes = new AtomicReference<>();
        try (final var fin = new FileInputStream(loc)) {
            final var keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(fin, password.toCharArray());
            StreamSupport.stream(
                            Spliterators.spliteratorUnknownSize(
                                    keyStore.aliases().asIterator(), 0),
                            false)
                    .forEach(alias -> {
                        try {
                            if (keyStore.isKeyEntry(alias) && alias.equals(certAlias)) {
                                final var cert = keyStore.getCertificate(alias);
                                if (cert instanceof X509Certificate x509Cert) {
                                    certBytes.set(x509Cert.getEncoded());
                                }
                            }
                        } catch (KeyStoreException | CertificateEncodingException e) {
                            throw new IllegalStateException(e);
                        }
                    });
        } catch (Exception e) {
            throw new CommandLine.ParameterException(
                    yahcli.getSpec().commandLine(), "Could not load a certificate from '" + loc + "'", e);
        }
        return Optional.ofNullable(certBytes.get()).orElseThrow();
    }

    private static Optional<File> passFileFor(@NonNull final File pfxFile) {
        final var absPath = pfxFile.getAbsolutePath();
        final var passFile = new File(absPath.replace(".pfx", ".pass"));
        return passFile.exists() ? Optional.of(passFile) : Optional.empty();
    }
}
