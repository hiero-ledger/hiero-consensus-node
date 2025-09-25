// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.token;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestMethodOrder;

@Tag(SMART_CONTRACT)
@Tag(MATS)
@DisplayName("transferToken")
@SuppressWarnings("java:S1192")
@HapiTestLifecycle
@TestMethodOrder(OrderAnnotation.class)
public class TransferTokenTest {

    @Contract(contract = "TokenTransferContract", creationGas = 4_000_000L)
    static SpecContract tokenTransferContract;

    @Contract(contract = "NestedHTSTransferrer", creationGas = 4_000_000L)
    static SpecContract tokenReceiverContract;

    @FungibleToken(name = "fungibleToken")
    static SpecFungibleToken fungibleToken;

    @Account(name = "account", tinybarBalance = 100 * ONE_HUNDRED_HBARS)
    static SpecAccount account;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        fungibleToken.builder().totalSupply(20L);
        fungibleToken.setTreasury(account);
    }

    /**
     * The behavior of the transferToken function and transferFrom function differs for contracts that are token owners.
     * The tests below highlight the differences and shows that an allowance approval is required for the transferFrom function
     * in order to be consistent with the ERC20 standard.
     */
    @Nested
    @DisplayName("successful when")
    @Order(1)
    class SuccessfulTransferTokenTest {
        @HapiTest
        @DisplayName("transferring owner's tokens using transferToken function without explicit allowance")
        public Stream<DynamicTest> transferUsingTransferToken() {
            return hapiTest(
                    tokenTransferContract.associateTokens(fungibleToken),
                    tokenReceiverContract.associateTokens(fungibleToken),
                    tokenTransferContract.receiveUnitsFrom(account, fungibleToken, 20L),
                    // Transfer using transferToken function
                    tokenTransferContract
                            .call(
                                    "transferTokenPublic",
                                    fungibleToken,
                                    tokenTransferContract,
                                    tokenReceiverContract,
                                    2L)
                            .gas(1_000_000L));
        }

        @HapiTest
        @DisplayName("transferring owner's tokens using transferFrom function given allowance")
        public Stream<DynamicTest> transferUsingTransferFromWithAllowance() {
            return hapiTest(
                    // Approve the transfer contract to spend 2 tokens
                    tokenTransferContract
                            .call("approvePublic", fungibleToken, tokenTransferContract, BigInteger.valueOf(2L))
                            .gas(1_000_000L),
                    // Transfer using transferFrom function
                    tokenTransferContract
                            .call(
                                    "transferFromPublic",
                                    fungibleToken,
                                    tokenTransferContract,
                                    tokenReceiverContract,
                                    BigInteger.valueOf(2L))
                            .gas(1_000_000L));
        }
    }

    @Nested
    @DisplayName("fails when")
    @Order(2)
    class FailedTransferTokenTest {
        @HapiTest
        @DisplayName("transferring owner's tokens using transferFrom function without allowance")
        public Stream<DynamicTest> transferUsingTransferFromWithoutAllowance() {
            return hapiTest(
                    // Transfer using transferFrom function without allowance should fail
                    tokenTransferContract
                            .call(
                                    "transferFromPublic",
                                    fungibleToken,
                                    tokenTransferContract,
                                    tokenReceiverContract,
                                    BigInteger.valueOf(2L))
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)));
        }

        @HapiTest
        @DisplayName("transferring owner's tokens using transferToken function from receiver contract")
        public Stream<DynamicTest> transferUsingTransferFromReceiver() {
            return hapiTest(
                    // Transfer using receiver contract transfer function should fail
                    tokenReceiverContract
                            .call("transfer", fungibleToken, tokenTransferContract, tokenReceiverContract, 2L)
                            .gas(1_000_000L)
                            .andAssert(txn -> txn.hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)));
        }
    }
}
