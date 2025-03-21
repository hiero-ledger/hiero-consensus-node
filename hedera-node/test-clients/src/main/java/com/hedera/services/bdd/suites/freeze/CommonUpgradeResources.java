// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.freeze;

import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.props.JutilPropertySource;
import com.hedera.services.bdd.suites.perf.PerfTestLoadSettings;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class CommonUpgradeResources {
    private static final String SHARD = JutilPropertySource.getDefaultInstance().get("default.shard");
    private static final String REALM = JutilPropertySource.getDefaultInstance().get("default.realm");
    public static final String DEFAULT_UPGRADE_FILE_ID = String.format("%s.%s.150", SHARD, REALM);
    public static final String DEFAULT_UPGRADE_FILE_PATH = "testfiles/poeticUpgrade.zip";
    // This file is inside the pretend ZIP and PREPARE_UPGRADE should put it in the current artifacts directory
    public static final String FAKE_UPGRADE_FILE_NAME = "MrBleaney.txt";
    public static final Path FAKE_ASSETS_LOC = Paths.get("testfiles/fake-upgrade-assets");
    public static final int DEFAULT_APPENDS_PER_BURST = 512;
    public static final int DEFAULT_UPGRADE_DELAY = 2;

    private static final PerfTestLoadSettings settings = new PerfTestLoadSettings();

    public static HapiSpecOperation[] initializeSettings() {
        final HapiSpecOperation[] ops = {
            withOpContext((spec, ignore) -> settings.setFrom(spec.setup().ciPropertiesMap())),
            logIt(ignore -> settings.toString())
        };
        return ops;
    }

    public static String upgradeFileId() {
        return DEFAULT_UPGRADE_FILE_ID;
    }

    public static String upgradeFilePath() {
        return settings.getProperty("upgradeFilePath", DEFAULT_UPGRADE_FILE_PATH);
    }

    public static int upgradeFileAppendsPerBurst() {
        return settings.getIntProperty("upgradeFileAppendsPerBurst", DEFAULT_APPENDS_PER_BURST);
    }

    public static byte[] upgradeFileHash() {
        return upgradeFileHashAt(Paths.get(upgradeFilePath()), true);
    }

    public static byte[] upgradeFileHashAt(@NonNull final Path path) {
        return upgradeFileHashAt(path, false);
    }

    /**
     * Given a path, returns the SHA-384 hash of its contents. If the path is invalid or the file cannot be read,
     * an empty byte array is returned when {@code suppressExceptions=true} and an exception is propagated otherwise.
     *
     * <p>We support suppressing exceptions because that is the behavior in JRS now and out of an abundance of
     * caution we don't want to change it until those tests are replaced.
     *
     * @param path the path to the file
     * @param suppressExceptions whether to suppress exceptions
     * @return the SHA-384 hash of the file's contents or an empty byte array if the file cannot be read
     */
    private static byte[] upgradeFileHashAt(@NonNull final Path path, final boolean suppressExceptions) {
        try {
            final var fileBytes = Files.readAllBytes(path);
            return noThrowSha384HashOf(fileBytes);
        } catch (final InvalidPathException | IOException e) {
            if (!suppressExceptions) {
                throw new RuntimeException(e);
            }
        }
        return new byte[0];
    }

    public static int upgradeDelay() {
        return settings.getIntProperty("upgradeDelay", DEFAULT_UPGRADE_DELAY);
    }
}
