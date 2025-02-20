// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.upgrade;

import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.copyUnchecked;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.guaranteedExtantDir;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.rm;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A utility operation that builds a upgrade zip file based on the last JAR assembled
 * with this commit of the services repo
 *
 * <p>It builds the upgrade file from maybe three ingredients,
 * <ol>
 *     <li>A {@code HederaNode.jar} obtained by unzipping the JAR at the given path
 *     and replacing the Services version key in <i>semantic-version.properties</i>
 *     with a given new version.</li>
 *     <li><b>(Optional)</b> A <i>settings.txt</i> at the given path.</li>
 *     <li><b>(Optional)</b> A <i>config.txt</i> obtained by removing a requested
 *     node from the target network's current <i>config.txt</i>. If this is specified,
 *     the target network must be a {@link SubProcessNetwork}, since this is
 *     the only case in which the test can meaningfully inspect and change the
 *     address book.</li>
 * </ol>
 */
public class BuildUpgradeZipOp extends UtilOp {
    private static final Path WORKING_PATH = Path.of("build");
    private static final Path UPGRADE_ZIP_PATH = WORKING_PATH.resolve("upgrade");
    public static final Path FAKE_UPGRADE_ZIP_LOC = WORKING_PATH.resolve("upgrade.zip");

    private final Path path;

    public BuildUpgradeZipOp(@NonNull final Path path) {
        this.path = requireNonNull(path);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        try {
            rm(UPGRADE_ZIP_PATH);
            rm(FAKE_UPGRADE_ZIP_LOC);
            copyFakeAssets(path, guaranteedExtantDir(UPGRADE_ZIP_PATH));
            zipDirectory(FAKE_UPGRADE_ZIP_LOC);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return false;
    }

    private static void zipDirectory(@NonNull final Path zipPath) throws IOException {
        try (final var zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
            try (final var files = Files.walk(UPGRADE_ZIP_PATH)) {
                files.filter(path -> !Files.isDirectory(path)).forEach(path -> {
                    final var zipEntry =
                            new ZipEntry(UPGRADE_ZIP_PATH.relativize(path).toString());
                    try {
                        zos.putNextEntry(zipEntry);
                        Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
    }

    private static void copyFakeAssets(@NonNull final Path from, @NonNull final Path to) {
        try (var files = Files.walk(from)) {
            files.filter(file -> !file.equals(from))
                    .forEach(file ->
                            copyUnchecked(file, to.resolve(file.getFileName().toString())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
