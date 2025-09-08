// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.ivy.suites;

import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.readBytesUnchecked;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.yahcli.commands.ivy.scenarios.FileScenario.DEFAULT_CONTENTS;
import static com.hedera.services.yahcli.commands.ivy.scenarios.FileScenario.NOVEL_FILE_NAME;
import static com.hedera.services.yahcli.commands.ivy.scenarios.FileScenario.PERSISTENT_FILE_NAME;
import static com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig.SCENARIO_PAYER_NAME;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.keys.ControlForKey;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.yahcli.commands.ivy.scenarios.FileScenario;
import com.hedera.services.yahcli.commands.ivy.scenarios.PersistentFile;
import com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig;
import com.hedera.services.yahcli.config.YahcliKeys;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class IvyFileScenarioSuite extends AbstractIvySuite {
    private static final Logger log = LogManager.getLogger(IvyFileScenarioSuite.class);

    private final boolean novel;
    private final String scenariosLoc;

    public IvyFileScenarioSuite(
            @NonNull final Map<String, String> specConfig,
            @NonNull final ScenariosConfig scenariosConfig,
            @NonNull final Supplier<Supplier<String>> nodeAccounts,
            @NonNull final Runnable persistUpdatedScenarios,
            @NonNull final YahcliKeys yahcliKeys,
            final boolean novel,
            @NonNull final String scenariosLoc) {
        super(specConfig, scenariosConfig, nodeAccounts, persistUpdatedScenarios, yahcliKeys);
        this.novel = novel;
        this.scenariosLoc = requireNonNull(scenariosLoc);
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(fileScenario());
    }

    final Stream<DynamicTest> fileScenario() {
        final var scenarios = getOrCreateScenarios();
        if (scenarios.getFile() == null) {
            scenarios.setFile(new FileScenario());
        }
        final var file = requireNonNull(scenariosConfig.getScenarios().getFile());
        if (file.getPersistent() == null) {
            final var persistentFile = new PersistentFile();
            persistentFile.setContents(scenariosLoc + File.separator + DEFAULT_CONTENTS);
            file.setPersistent(persistentFile);
        }
        final var contentsLoc = file.getPersistent().getContents();
        final var path = Paths.get(contentsLoc);
        if (!path.toFile().exists()) {
            try (final var in = Thread.currentThread().getContextClassLoader().getResourceAsStream(DEFAULT_CONTENTS)) {
                requireNonNull(in);
                Files.copy(in, path);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Cannot copy classpath resource '" + DEFAULT_CONTENTS + "' to '" + contentsLoc + "'", e);
            }
        }
        final var contents = readBytesUnchecked(path);
        return HapiSpec.customHapiSpec("FileScenario")
                .withProperties(specConfig)
                .given(ensureScenarioPayer())
                .when(ensureEd25519File(
                        PERSISTENT_FILE_NAME, file.getPersistent().getNum(), contents, file.getPersistent()::setNum))
                .then(novelFileIfDesired());
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }

    private HapiSpecOperation[] novelFileIfDesired() {
        if (!novel) {
            return new HapiSpecOperation[] {};
        }
        final var firstComplex = KeyShape.listOf(KeyShape.threshOf(2, 3), KeyShape.threshOf(1, 3));
        final var secondComplex = KeyShape.listOf(3);
        final var revocation = secondComplex.signedWith(KeyShape.sigs(ON, OFF, OFF));
        return new HapiSpecOperation[] {
            newKeyNamed("novelFileFirstKey").shape(firstComplex),
            newKeyNamed("novelFileSecondKey").shape(secondComplex),
            fileCreate(NOVEL_FILE_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(nodeAccounts.get())
                    .key("novelFileFirstKey")
                    .contents("abcdefghijklm"),
            fileAppend(NOVEL_FILE_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(nodeAccounts.get())
                    .content("nopqrstuvwxyz"),
            getFileContents(NOVEL_FILE_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(nodeAccounts.get())
                    .hasContents(ignore -> "abcdefghijklmnopqrstuvwxyz".getBytes()),
            fileUpdate(NOVEL_FILE_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(nodeAccounts.get())
                    .wacl("novelFileSecondKey"),
            fileDelete(NOVEL_FILE_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(nodeAccounts.get())
                    .sigControl(ControlForKey.forKey(NOVEL_FILE_NAME, revocation))
        };
    }
}
