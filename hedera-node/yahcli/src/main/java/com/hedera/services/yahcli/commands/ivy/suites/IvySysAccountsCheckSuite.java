// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.ivy.suites;

import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.suites.HapiSuite;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

/**
 * Implements the {@link com.hedera.services.yahcli.commands.ivy.SysAccountsCheckCommand}.
 */
public class IvySysAccountsCheckSuite extends HapiSuite {
    private static final long FIRST_SYSTEM_FILE_ENTITY = 101L;
    private static final long FIRST_POST_SYSTEM_FILE_ENTITY = 200L;

    private static final Logger log = LogManager.getLogger(IvySysAccountsCheckSuite.class);

    private final Map<String, String> specConfig;

    public IvySysAccountsCheckSuite(@NonNull final Map<String, String> specConfig) {
        this.specConfig = requireNonNull(specConfig);
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(legacyAccountsAreMissing());
    }

    final Stream<DynamicTest> legacyAccountsAreMissing() {
        return HapiSpec.customHapiSpec("LegacyAccountsAreMissing")
                .withProperties(specConfig)
                .given()
                .when()
                .then(sourcingContextual(
                        spec -> inParallel(LongStream.range(FIRST_SYSTEM_FILE_ENTITY, FIRST_POST_SYSTEM_FILE_ENTITY)
                                        .mapToObj(n -> getAccountInfo(asAccountString(
                                                        spec.accountIdFactory().apply(n)))
                                                .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID))
                                        .toArray(SpecOperation[]::new))
                                .failOnErrors()));
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
