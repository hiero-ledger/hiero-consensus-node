// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import static com.hedera.services.yahcli.suites.Utils.extractAccount;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiSuite;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class RekeySuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(RekeySuite.class);

    private final String account;
    private final String replKeyLoc;
    private final String replTarget;
    private final boolean genNewKey;
    private final SigControl sigType;
    private final Map<String, String> specConfig;

    public RekeySuite(
            Map<String, String> specConfig,
            String account,
            String replKeyLoc,
            boolean genNewKey,
            SigControl sigType,
            String replTarget) {
        this.specConfig = specConfig;
        this.replKeyLoc = replKeyLoc;
        this.genNewKey = genNewKey;
        this.replTarget = replTarget;
        this.sigType = sigType;
        this.account = extractAccount(account);
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(rekey());
    }

    final Stream<DynamicTest> rekey() {
        final var currKey = "currKey";
        final var currKeyLoc = replTarget.endsWith(".pem") ? replTarget : replTarget.replace(".pem", ".words");
        final var replKey = "replKey";
        final var newKeyPass = TxnUtils.randomAlphaNumeric(12);

        return HapiSpec.customHapiSpec("rekey" + account)
                .withProperties(specConfig)
                .given(
                        // First load the current key (before overwriting it). Its type at this point is unknown
                        UtilVerbs.keyFromFile(currKey, currKeyLoc),
                        genNewKey
                                ? UtilVerbs.newKeyNamed(replKey)
                                        .shape(sigType)
                                        // Will overwrite the current key with the new key
                                        .exportingTo(currKeyLoc, newKeyPass)
                                        .yahcliLogged()
                                : UtilVerbs.keyFromFile(replKey, replKeyLoc)
                                        // Will overwrite the current key with the new key
                                        .exportingTo(currKeyLoc, newKeyPass)
                                        .yahcliLogged())
                .when(TxnVerbs.cryptoUpdate(account)
                        .signedBy(HapiSuite.DEFAULT_PAYER, currKey, replKey)
                        .key(replKey)
                        .yahcliLogging())
                .then(UtilVerbs.withOpContext((spec, opLog) -> {
                    if (replTarget.endsWith(".words")) {
                        new File(replTarget).delete();
                    }
                }));
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
