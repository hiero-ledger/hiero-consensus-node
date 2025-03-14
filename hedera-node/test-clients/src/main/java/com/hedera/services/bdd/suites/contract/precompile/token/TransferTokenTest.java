// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.token;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromToWithAlias;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.leaky.LeakyContractTestsSuite.TOKEN_TRANSFER_CONTRACT;
import static com.hedera.services.bdd.suites.contract.precompile.ContractHTSSuite.TOKEN_TRANSFERS_CONTRACT;
import static com.hedera.services.bdd.suites.contract.precompile.ContractHTSSuite.TRANSFER_TOKEN;
import static com.hedera.services.bdd.suites.contract.precompile.CreatePrecompileSuite.ECDSA_KEY;
import static com.hedera.services.bdd.suites.contract.precompile.TokenExpiryInfoSuite.GAS_TO_OFFER;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.FUNGIBLE_TOKEN;
import static com.hedera.services.bdd.suites.crypto.CryptoCreateSuite.ACCOUNT;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;

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
@DisplayName("transferToken")
@SuppressWarnings("java:S1192")
@HapiTestLifecycle
@TestMethodOrder(OrderAnnotation.class)
public class TransferTokenTest {

    @Contract(contract = "TokenTransferContract", creationGas = 1_000_000L)
    static SpecContract tokenTransferContract;

    @Contract(contract = "NestedHTSTransferrer", creationGas = 1_000_000L)
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

    @Order(3)
    @HapiTest
    @DisplayName("Should transfer to the same account by long zero address and by EVM address")
    final Stream<DynamicTest> tryTransferTokenToAlias() {
        return hapiTest(
                newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, ECDSA_KEY, ONE_HUNDRED_HBARS)),
                withOpContext((spec, opLog) -> updateSpecFor(spec, ECDSA_KEY)),
                cryptoTransfer(tinyBarsFromToWithAlias(GENESIS, ECDSA_KEY, ONE_HUNDRED_HBARS)),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1_000L)
                        .treasury(TOKEN_TREASURY),
                uploadInitCode(TOKEN_TRANSFERS_CONTRACT),
                contractCreate(TOKEN_TRANSFERS_CONTRACT).gas(GAS_TO_OFFER),
                tokenAssociate(ACCOUNT, FUNGIBLE_TOKEN),
                tokenAssociate(ECDSA_KEY, FUNGIBLE_TOKEN),
                tokenAssociate(TOKEN_TRANSFER_CONTRACT, FUNGIBLE_TOKEN),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addTokenAllowance(ACCOUNT, FUNGIBLE_TOKEN, TOKEN_TRANSFERS_CONTRACT, 200L)
                        .signedBy(DEFAULT_PAYER, ACCOUNT)
                        .fee(ONE_HBAR),
                cryptoTransfer(moving(200L, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, ACCOUNT)),
                cryptoTransfer(moving(200L, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, ECDSA_KEY)),
                withOpContext((spec, opLog) -> {
                    final var token =
                            asHeadlongAddress(asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN)));
                    final var sender =
                            asHeadlongAddress(asAddress(spec.registry().getAccountID(ACCOUNT)));
                    final var ecdsaLongZeroAddress =
                            asHeadlongAddress(asAddress(spec.registry().getAccountID(ECDSA_KEY)));
                    final var ecdsaKey = spec.registry().getKey(ECDSA_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var ecdsaAddress = asHeadlongAddress(recoverAddressFromPubKey(tmp));

                    allRunFor(
                            spec,
                            contractCall(
                                            TOKEN_TRANSFERS_CONTRACT,
                                            TRANSFER_TOKEN,
                                            token,
                                            sender,
                                            ecdsaLongZeroAddress,
                                            2L)
                                    .payingWith(DEFAULT_PAYER)
                                    .gas(GAS_TO_OFFER)
                                    .via("transferToLongZeroTxn"));
                    allRunFor(
                            spec,
                            contractCall(TOKEN_TRANSFERS_CONTRACT, TRANSFER_TOKEN, token, sender, ecdsaAddress, 2L)
                                    .payingWith(DEFAULT_PAYER)
                                    .gas(GAS_TO_OFFER)
                                    .via("transferToEVMAddressTxn"));
                }));
    }
}
