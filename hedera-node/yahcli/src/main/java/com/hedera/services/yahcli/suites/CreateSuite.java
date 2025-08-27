// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.spec.utilops.inventory.NewSpecKey;
import com.hedera.services.bdd.suites.HapiSuite;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class CreateSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(CreateSuite.class);

    public static String NOVELTY = "novel";

    private final Map<String, String> specConfig;
    private final String memo;
    private final long initialBalance;
    private final SigControl sigType;
    private final int numBusyRetries;

    private final boolean receiverSigRequired;

    private final String novelTarget;
    private final String keyLoc;
    private final String passLoc;

    private final AtomicLong createdNo = new AtomicLong(0);

    public CreateSuite(
            final Map<String, String> specConfig,
            final long initialBalance,
            final String memo,
            final String novelTarget,
            final SigControl sigType,
            final int numBusyRetries,
            final boolean receiverSigRequired,
            final String keyLoc,
            final String passLoc) {
        this.memo = memo;
        this.specConfig = specConfig;
        this.novelTarget = novelTarget;
        this.sigType = sigType;
        this.numBusyRetries = numBusyRetries;
        this.initialBalance = initialBalance;
        this.receiverSigRequired = receiverSigRequired;
        this.keyLoc = keyLoc;
        this.passLoc = passLoc;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(doCreate());
    }

    final Stream<DynamicTest> doCreate() {
        if (!novelTarget.endsWith(NOVELTY + ".pem")) {
            throw new IllegalArgumentException("Only accepts tentative new key material named 'novel.pem'");
        }

        final var newAccount = "newAccount";
        final var newKey = "newKey";
        final var success = new AtomicBoolean(false);
        final var novelPass = TxnUtils.randomAlphaNumeric(12);

        // Default operation of creating a new key is assigned
        NewSpecKey newSpecKey = UtilVerbs.newKeyNamed(newKey)
                .exportingTo(novelTarget, novelPass)
                .shape(sigType);
        newSpecKey = (sigType == SigControl.ED25519_ON) ? newSpecKey.includingEd25519Mnemonic() : newSpecKey;

        // If an existing key is specified, use that instead of generating a new one
        SpecOperation keyOp = newSpecKey;
        if (usingExistingKey()) {
            keyOp = UtilVerbs.keyFromFile(newKey, keyLoc);
        }

        return HapiSpec.customHapiSpec("DoCreate")
                .withProperties(specConfig)
                .given(keyOp)
                .when(UtilVerbs.withOpContext((spec, opLog) -> {
                    int attemptNo = 1;
                    do {
                        System.out.print("Creation attempt #" + attemptNo + "...");
                        var creation = TxnVerbs.cryptoCreate(newAccount)
                                .balance(initialBalance)
                                .blankMemo()
                                .entityMemo(memo)
                                .key(newKey)
                                .receiverSigRequired(receiverSigRequired)
                                .hasPrecheckFrom(OK, BUSY)
                                .exposingCreatedIdTo(id -> createdNo.set(id.getAccountNum()))
                                .noLogging();
                        creation = (sigType == SigControl.SECP256K1_ON) ? creation.withMatchingEvmAddress() : creation;
                        CustomSpecAssert.allRunFor(spec, creation);
                        if (creation.getActualPrecheck() == OK) {
                            System.out.println("SUCCESS");
                            success.set(true);
                            break;
                        } else {
                            final var retriesLeft = numBusyRetries - attemptNo + 1;
                            System.out.println("BUSY"
                                    + (retriesLeft > 0
                                            ? ", retrying " + retriesLeft + " more times"
                                            : " again, giving up"));
                        }
                    } while (attemptNo++ <= numBusyRetries);
                }))
                .then(UtilVerbs.withOpContext((spec, opLog) -> {
                    final boolean completedSuccessfully = success.get();
                    if (completedSuccessfully && usingExistingKey()) {
                        System.out.println(".i. Key '" + keyLoc + "' used for new account");
                    } else if (completedSuccessfully) {
                        final var locs = new ArrayList<String>() {
                            {
                                add(novelTarget);
                                add(novelTarget.replace(".pem", ".pass"));
                            }
                        };
                        if (sigType == SigControl.ED25519_ON) {
                            locs.add(novelTarget.replace(".pem", ".words"));
                        }
                        final var accountId = "account" + createdNo.get();
                        for (final var loc : locs) {
                            try (final var fin = Files.newInputStream(Paths.get(loc))) {
                                fin.transferTo(Files.newOutputStream(Paths.get(loc.replace(NOVELTY, accountId))));
                            }
                            new File(loc).delete();
                        }
                    }
                }));
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }

    public AtomicLong getCreatedNo() {
        return createdNo;
    }

    public static boolean existingKeyPresent(String keyLoc, String passLoc) {
        return keyLoc != null && !keyLoc.isBlank() && passLoc != null && !passLoc.isBlank();
    }

    private boolean usingExistingKey() {
        return existingKeyPresent(keyLoc, passLoc);
    }
}
