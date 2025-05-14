// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.issues;

import static com.hedera.services.bdd.junit.ContextRequirement.SYSTEM_ACCOUNT_BALANCES;
import static com.hedera.services.bdd.spec.HapiPropertySource.asContract;
import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.bdd.spec.HapiPropertySource.asFile;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.takeBalanceSnapshots;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateTransferListForBalances;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.NODE;
import static com.hedera.services.bdd.suites.HapiSuite.STAKING_REWARD;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.keys.KeyFactory;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

public class Issue1765Suite {
    private static final String ACCOUNT = "1.1.1";
    private static final String INVALID_UPDATE_TXN = "invalidUpdateTxn";
    private static final String INVALID_APPEND_TXN = "invalidAppendTxn";
    private static final String IMAGINARY = "imaginary";
    private static final String MEMO_IS = "Turning and turning in the widening gyre";

    @LeakyHapiTest(requirement = SYSTEM_ACCOUNT_BALANCES)
    final Stream<DynamicTest> recordOfInvalidContractUpdateSanityChecks() {
        final long ADEQUATE_FEE = 100_000_000L;
        final String INVALID_CONTRACT = IMAGINARY;
        final String THE_MEMO_IS = MEMO_IS;

        return hapiTest(flattened(
                withOpContext((spec, ctxLog) -> spec.registry().saveContractId(INVALID_CONTRACT, asContract(ACCOUNT))),
                newKeyNamed(INVALID_CONTRACT),
                takeBalanceSnapshots(FUNDING, GENESIS, NODE),
                contractUpdate(INVALID_CONTRACT)
                        .memo(THE_MEMO_IS)
                        .fee(ADEQUATE_FEE)
                        .via(INVALID_UPDATE_TXN)
                        .hasKnownStatus(ResponseCodeEnum.INVALID_CONTRACT_ID),
                validateTransferListForBalances(INVALID_UPDATE_TXN, List.of(FUNDING, GENESIS, NODE)),
                getTxnRecord(INVALID_UPDATE_TXN).hasPriority(recordWith().memo(THE_MEMO_IS))));
    }

    @LeakyHapiTest(requirement = SYSTEM_ACCOUNT_BALANCES)
    final Stream<DynamicTest> recordOfInvalidFileUpdateSanityChecks() {
        final long ADEQUATE_FEE = 100_000_000L;
        final String INVALID_FILE = IMAGINARY;
        final String THE_MEMO_IS = MEMO_IS;

        return hapiTest(flattened(
                withOpContext((spec, ctxLog) -> spec.registry()
                        .saveFileId(INVALID_FILE, asFile(asEntityString(spec.shard(), spec.realm(), 0)))),
                newKeyNamed(INVALID_FILE).type(KeyFactory.KeyType.LIST),
                takeBalanceSnapshots(FUNDING, GENESIS, STAKING_REWARD, NODE),
                fileUpdate(INVALID_FILE)
                        .memo(THE_MEMO_IS)
                        .fee(ADEQUATE_FEE)
                        .via(INVALID_UPDATE_TXN)
                        .hasKnownStatus(ResponseCodeEnum.INVALID_FILE_ID),
                validateTransferListForBalances(INVALID_UPDATE_TXN, List.of(FUNDING, GENESIS, STAKING_REWARD, NODE)),
                getTxnRecord(INVALID_UPDATE_TXN).hasPriority(recordWith().memo(THE_MEMO_IS))));
    }

    @LeakyHapiTest(requirement = SYSTEM_ACCOUNT_BALANCES)
    final Stream<DynamicTest> recordOfInvalidFileAppendSanityChecks() {
        final long ADEQUATE_FEE = 100_000_000L;
        final String INVALID_FILE = IMAGINARY;
        final String THE_MEMO_IS = MEMO_IS;

        return hapiTest(flattened(
                withOpContext((spec, ctxLog) -> spec.registry()
                        .saveFileId(INVALID_FILE, asFile(asEntityString(spec.shard(), spec.realm(), 0)))),
                newKeyNamed(INVALID_FILE).type(KeyFactory.KeyType.LIST),
                takeBalanceSnapshots(FUNDING, GENESIS, STAKING_REWARD, NODE),
                fileAppend(INVALID_FILE)
                        .memo(THE_MEMO_IS)
                        .content("Some more content.")
                        .fee(ADEQUATE_FEE)
                        .via(INVALID_APPEND_TXN)
                        .hasKnownStatus(ResponseCodeEnum.INVALID_FILE_ID),
                validateTransferListForBalances(INVALID_APPEND_TXN, List.of(FUNDING, GENESIS, STAKING_REWARD, NODE)),
                getTxnRecord(INVALID_APPEND_TXN).hasPriority(recordWith().memo(THE_MEMO_IS))));
    }
}
