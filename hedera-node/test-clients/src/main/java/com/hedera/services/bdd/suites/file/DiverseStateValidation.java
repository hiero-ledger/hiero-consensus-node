// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.file;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.systemFileUndelete;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.file.DiverseStateCreation.ENTITY_NUM_KEY;
import static com.hedera.services.bdd.suites.file.DiverseStateCreation.EXPECTED_LUCKY_NO;
import static com.hedera.services.bdd.suites.file.DiverseStateCreation.FUSE_BYTECODE;
import static com.hedera.services.bdd.suites.file.DiverseStateCreation.FUSE_CONTRACT;
import static com.hedera.services.bdd.suites.file.DiverseStateCreation.FUSE_INITCODE;
import static com.hedera.services.bdd.suites.file.DiverseStateCreation.HEXED_BYTECODE_KEY;
import static com.hedera.services.bdd.suites.file.DiverseStateCreation.KEY_REPRS_KEY;
import static com.hedera.services.bdd.suites.file.DiverseStateCreation.LARGE_CONTENTS_LOC;
import static com.hedera.services.bdd.suites.file.DiverseStateCreation.LARGE_FILE;
import static com.hedera.services.bdd.suites.file.DiverseStateCreation.MEDIUM_FILE;
import static com.hedera.services.bdd.suites.file.DiverseStateCreation.MULTI_CONTRACT;
import static com.hedera.services.bdd.suites.file.DiverseStateCreation.MULTI_INITCODE;
import static com.hedera.services.bdd.suites.file.DiverseStateCreation.SMALL_CONTENTS_LOC;
import static com.hedera.services.bdd.suites.file.DiverseStateCreation.SMALL_FILE;
import static com.hedera.services.bdd.suites.file.DiverseStateCreation.STATE_META_JSON_LOC;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hedera.services.bdd.suites.HapiSuite;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.utility.CommonUtils;
import org.junit.jupiter.api.DynamicTest;

/**
 * Client that validates the blobs mentioned in a JSON metadata file created by {@link
 * DiverseStateCreation} are present in state as expected (e.g. after a migration).
 */
public final class DiverseStateValidation extends HapiSuite {
    private static final Logger log = LogManager.getLogger(DiverseStateValidation.class);

    private static byte[] SMALL_CONTENTS;
    private static byte[] LARGE_CONTENTS;

    public static void main(String... args) throws IOException {
        new DiverseStateValidation().runSuiteSync();
    }

    private final AtomicReference<Map<String, Integer>> entityNums = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> keyReprs = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> hexedBytecode = new AtomicReference<>();

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        try {
            SMALL_CONTENTS = Files.newInputStream(Paths.get(SMALL_CONTENTS_LOC)).readAllBytes();
            LARGE_CONTENTS = Files.newInputStream(Paths.get(LARGE_CONTENTS_LOC)).readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return List.of(validateDiverseState());
    }

    @SuppressWarnings("unchecked")
    Stream<DynamicTest> validateDiverseState() {
        return hapiTest(
                withOpContext((spec, opLog) -> {
                    final var om = new ObjectMapper();
                    final var meta = (Map<String, Object>)
                            om.readValue(Files.newInputStream(Paths.get(STATE_META_JSON_LOC)), Map.class);
                    entityNums.set((Map<String, Integer>) meta.get(ENTITY_NUM_KEY));
                    keyReprs.set((Map<String, String>) meta.get(KEY_REPRS_KEY));
                    hexedBytecode.set((Map<String, String>) meta.get(HEXED_BYTECODE_KEY));
                }),
                sourcing(() -> systemFileUndelete(idLiteralWith(entityNums.get().get(FUSE_INITCODE)))
                        .payingWith(GENESIS)),
                sourcing(() -> systemFileUndelete(idLiteralWith(entityNums.get().get(MULTI_INITCODE)))
                        .payingWith(GENESIS)),
                /* Confirm un-deletion recovered expiry times */
                sourcing(() -> getFileInfo(idLiteralWith(entityNums.get().get(FUSE_INITCODE)))
                        .hasExpiry(() -> DiverseStateCreation.FUSE_EXPIRY_TIME)),
                sourcing(() -> getFileInfo(idLiteralWith(entityNums.get().get(MULTI_INITCODE)))
                        .hasExpiry(() -> DiverseStateCreation.MULTI_EXPIRY_TIME)),
                /* Confirm misc file meta and contents */
                sourcing(() -> getFileInfo(idLiteralWith(entityNums.get().get(SMALL_FILE)))
                        .hasKeyReprTo(keyReprs.get().get(SMALL_FILE))
                        .hasExpiry(() -> DiverseStateCreation.SMALL_EXPIRY_TIME)
                        .hasDeleted(false)),
                sourcing(() -> getFileContents(idLiteralWith(entityNums.get().get(SMALL_FILE)))
                        .hasContents(ignore -> SMALL_CONTENTS)),
                sourcing(() -> getFileInfo(idLiteralWith(entityNums.get().get(MEDIUM_FILE)))
                        .hasKeyReprTo(keyReprs.get().get(MEDIUM_FILE))
                        .hasExpiry(() -> DiverseStateCreation.MEDIUM_EXPIRY_TIME)
                        .hasDeleted(true)),
                logIt("--- Now validating large file ---"),
                sourcing(() -> getFileInfo(idLiteralWith(entityNums.get().get(LARGE_FILE)))
                        .hasKeyReprTo(keyReprs.get().get(LARGE_FILE))
                        .hasExpiry(() -> DiverseStateCreation.LARGE_EXPIRY_TIME)
                        .hasDeleted(false)),
                sourcing(() -> getFileContents(idLiteralWith(entityNums.get().get(LARGE_FILE)))
                        .hasContents(ignore -> LARGE_CONTENTS)),
                /* Confirm contract code and behavior */
                logIt("--- Now validating contract stuff ---"),
                sourcing(() -> getContractBytecode(
                                idLiteralWith(entityNums.get().get(FUSE_CONTRACT)))
                        .hasBytecode(CommonUtils.unhex(hexedBytecode.get().get(FUSE_BYTECODE)))),
                sourcing(() -> contractCallLocal(idLiteralWith(entityNums.get().get(MULTI_CONTRACT)), "pick")
                        .has(resultWith()
                                .resultThruAbi(
                                        getABIFor(FUNCTION, "pick", MULTI_CONTRACT),
                                        isLiteralResult(new Object[] {BigInteger.valueOf(EXPECTED_LUCKY_NO)})))));
    }

    private String idLiteralWith(long num) {
        return "0.0." + num;
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
