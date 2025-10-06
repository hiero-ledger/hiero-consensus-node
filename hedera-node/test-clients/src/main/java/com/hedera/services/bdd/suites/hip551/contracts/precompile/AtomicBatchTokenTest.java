// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551.contracts.precompile;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.dsl.entities.SpecContract.VARIANT_16C;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.ADMIN_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.SUPPLY_KEY;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.KEY_NOT_PROVIDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@HapiTestLifecycle
public class AtomicBatchTokenTest {
    private static final String DEFAULT_BATCH_OPERATOR = "defaultBatchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        // enable atomic batch
        testLifecycle.overrideInClass(Map.of(
                "atomicBatch.isEnabled", "true",
                "atomicBatch.maxNumberOfTransactions", "50",
                "contracts.throttle.throttleByGas", "false"));
        // create default batch operator
        testLifecycle.doAdhoc(cryptoCreate(DEFAULT_BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    /**
     * GetTokenKeyPrecompileTest
     */
    @HapiTest
    @DisplayName("atomic cannot get a nonsense key type")
    public Stream<DynamicTest> atomicCannotGetNonsenseKeyType(
            @Contract(contract = "UpdateTokenInfoContract", creationGas = 4_000_000L)
                    final SpecContract getTokenKeyContract,
            @NonFungibleToken(
                            numPreMints = 1,
                            keys = {ADMIN_KEY, SpecTokenKey.SUPPLY_KEY, SpecTokenKey.METADATA_KEY})
                    final SpecNonFungibleToken nonFungibleToken,
            @Contract(contract = "UpdateTokenInfoContract", creationGas = 4_000_000L, variant = VARIANT_16C)
                    final SpecContract getTokenKeyContract16c) {
        return hapiTest(
                getTokenKeyContract
                        .call("getKeyFromToken", nonFungibleToken, BigInteger.valueOf(123L))
                        .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR, OK, INNER_TRANSACTION_FAILED)
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, KEY_NOT_PROVIDED)),
                getTokenKeyContract16c
                        .call("getKeyFromToken", nonFungibleToken, BigInteger.valueOf(123L))
                        .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR, OK, INNER_TRANSACTION_FAILED)
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, KEY_NOT_PROVIDED)));
    }

    /**
     * TransferTokenTest
     */
    @HapiTest
    @DisplayName("atomic transferring owner's tokens using transferToken function without explicit allowance")
    @Tag(MATS)
    public Stream<DynamicTest> atomicTransferUsingTransferToken(
            @Contract(contract = "TokenTransferContract", creationGas = 10_000_000L)
                    final SpecContract tokenTransferContract,
            @Contract(contract = "NestedHTSTransferrer", creationGas = 10_000_000L)
                    final SpecContract tokenReceiverContract,
            @FungibleToken(name = "fungibleToken") final SpecFungibleToken fungibleToken,
            @Account(name = "account", tinybarBalance = 1000 * ONE_HUNDRED_HBARS) final SpecAccount account) {
        fungibleToken.builder().totalSupply(40L);
        fungibleToken.setTreasury(account);
        return hapiTest(
                tokenTransferContract.associateTokens(fungibleToken),
                tokenReceiverContract.associateTokens(fungibleToken),
                tokenTransferContract.receiveUnitsFrom(account, fungibleToken, 20L),
                // Transfer using transferToken function
                tokenTransferContract
                        .call("transferTokenPublic", fungibleToken, tokenTransferContract, tokenReceiverContract, 2L)
                        .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR)
                        .gas(1_000_000L));
    }

    /**
     * UpdateTokenPrecompileTest
     */
    @HapiTest
    @DisplayName("atomic can update the name")
    public Stream<DynamicTest> atomicCanUpdateName(
            @Contract(contract = "TokenInfoSingularUpdate", creationGas = 4_000_000L)
                    final SpecContract updateTokenPropertyContract,
            @NonFungibleToken(
                            numPreMints = 1,
                            keys = {ADMIN_KEY, SUPPLY_KEY})
                    final SpecNonFungibleToken sharedMutableToken) {
        return hapiTest(
                sharedMutableToken.authorizeContracts(updateTokenPropertyContract),
                updateTokenPropertyContract
                        .call("updateTokenName", sharedMutableToken, "NEW_NAME")
                        .wrappedInBatchOperation(DEFAULT_BATCH_OPERATOR),
                sharedMutableToken.getInfo().andAssert(info -> info.hasName("NEW_NAME")));
    }
}
