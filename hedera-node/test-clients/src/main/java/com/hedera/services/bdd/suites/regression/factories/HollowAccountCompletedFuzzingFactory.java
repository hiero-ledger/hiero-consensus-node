// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.factories;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.spec.infrastructure.OpProvider.UNIQUE_PAYER_ACCOUNT;
import static com.hedera.services.bdd.spec.infrastructure.OpProvider.UNIQUE_PAYER_ACCOUNT_INITIAL_BALANCE;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.CRYPTO_TRANSFER_RECEIVER;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.LAZY_CREATE_SPONSOR;
import static com.hedera.services.bdd.suites.regression.factories.RegressionProviderFactory.intPropOrElse;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.listeners.TokenAccountRegistryRel;
import com.hedera.services.bdd.spec.infrastructure.meta.ActionableContractCall;
import com.hedera.services.bdd.spec.infrastructure.meta.ActionableContractCallLocal;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.BiasedDelegatingProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.consensus.RandomMessageSubmit;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.consensus.RandomTopicCreation;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.consensus.RandomTopicDeletion;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.consensus.RandomTopicInfo;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.consensus.RandomTopicUpdate;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.contract.RandomCall;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.contract.RandomCallLocal;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.contract.RandomContract;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.contract.RandomContractDeletion;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.files.RandomFile;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.schedule.RandomSchedule;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.schedule.RandomScheduleDeletion;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.schedule.RandomScheduleSign;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomToken;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenAccountWipe;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenAssociation;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenBurn;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenDeletion;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenDissociation;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenFreeze;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenKycGrant;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenKycRevoke;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenMint;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenTransfer;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenUnfreeze;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenUpdate;
import com.hedera.services.bdd.spec.infrastructure.selectors.RandomSelector;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TopicID;
import java.util.function.Function;

public class HollowAccountCompletedFuzzingFactory {
    public static final String CONTRACT = "PayReceivable";
    public static final String VANILLA_TOKEN = "TokenD";
    public static final String TOKEN_TREASURY = "treasury";
    public static final String HOLLOW_ACCOUNT = "hollowAccount";

    private HollowAccountCompletedFuzzingFactory() {
        throw new IllegalStateException("Static factory class");
    }

    /**
     * Initialization operations:
     * create accounts for sending and receiving transfers (LAZY_CREATE_SPONSOR, CRYPTO_TRANSFER_RECEIVER).
     * initiate contract for contract create and token for token associate.
     *
     * @return array of the initialization operations
     */
    public static HapiSpecOperation[] initOperations() {
        return new HapiSpecOperation[] {
            cryptoCreate(LAZY_CREATE_SPONSOR)
                    .balance(UNIQUE_PAYER_ACCOUNT_INITIAL_BALANCE)
                    .withRecharging(),
            cryptoCreate(CRYPTO_TRANSFER_RECEIVER)
                    .balance(UNIQUE_PAYER_ACCOUNT_INITIAL_BALANCE)
                    .withRecharging(),
            cryptoCreate(UNIQUE_PAYER_ACCOUNT)
                    .balance(UNIQUE_PAYER_ACCOUNT_INITIAL_BALANCE)
                    .withRecharging(),
            uploadInitCode(CONTRACT),
            cryptoCreate(TOKEN_TREASURY).balance(0L),
            contractCreate(CONTRACT)
        };
    }

    public static Function<HapiSpec, OpProvider> hollowAccountFuzzingWith(final String resource) {
        return spec -> {
            final var props = RegressionProviderFactory.propsFrom(resource);

            final var accounts = new RegistrySourcedNameProvider<>(
                    AccountID.class, spec.registry(), new RandomSelector(account -> account.equals(HOLLOW_ACCOUNT)));
            final var tokens = new RegistrySourcedNameProvider<>(TokenID.class, spec.registry(), new RandomSelector());
            var tokenRels = new RegistrySourcedNameProvider<>(
                    TokenAccountRegistryRel.class, spec.registry(), new RandomSelector());
            var allTopics = new RegistrySourcedNameProvider<>(TopicID.class, spec.registry(), new RandomSelector());
            var unstableTopics = new RegistrySourcedNameProvider<>(
                    TopicID.class, spec.registry(), new RandomSelector(topic -> !topic.startsWith("stable-")));
            var allSchedules =
                    new RegistrySourcedNameProvider<>(ScheduleID.class, spec.registry(), new RandomSelector());
            var contracts = new RegistrySourcedNameProvider<>(ContractID.class, spec.registry(), new RandomSelector());
            var calls = new RegistrySourcedNameProvider<>(
                    ActionableContractCall.class, spec.registry(), new RandomSelector());
            var localCalls = new RegistrySourcedNameProvider<>(
                    ActionableContractCallLocal.class, spec.registry(), new RandomSelector());

            final var keys = new RegistrySourcedNameProvider<>(
                    Key.class, spec.registry(), new RandomSelector(account -> account.equals(HOLLOW_ACCOUNT)));
            final var emptyCustomOutcomes = new ResponseCodeEnum[] {};
            return new BiasedDelegatingProvider()
                    .shouldLogNormalFlow(true)
                    .withInitialization(
                            newKeyNamed(HOLLOW_ACCOUNT).shape(SigControl.SECP256K1_ON),
                            generateHollowAccount(),
                            completeHollowAccount())

                    /* ---- CONSENSUS ---- */
                    .withOp(
                            new RandomTopicCreation(keys, allTopics, emptyCustomOutcomes)
                                    .ceiling(intPropOrElse(
                                                    "randomTopicCreation.ceilingNum",
                                                    RandomFile.DEFAULT_CEILING_NUM,
                                                    props)
                                            + intPropOrElse(
                                                    "randomMessageSubmit.numStableTopics",
                                                    RandomMessageSubmit.DEFAULT_NUM_STABLE_TOPICS,
                                                    props)),
                            intPropOrElse("randomTopicCreation.bias", 0, props))
                    .withOp(
                            new RandomTopicDeletion(unstableTopics, emptyCustomOutcomes),
                            intPropOrElse("randomTopicDeletion.bias", 0, props))
                    .withOp(
                            new RandomTopicUpdate(unstableTopics, emptyCustomOutcomes),
                            intPropOrElse("randomTopicUpdate.bias", 0, props))
                    .withOp(
                            new RandomMessageSubmit(allTopics)
                                    .numStableTopics(intPropOrElse(
                                            "randomMessageSubmit.numStableTopics",
                                            RandomMessageSubmit.DEFAULT_NUM_STABLE_TOPICS,
                                            props)),
                            intPropOrElse("randomMessageSubmit.bias", 0, props))
                    .withOp(new RandomTopicInfo(allTopics), intPropOrElse("randomTopicInfo.bias", 0, props))
                    /* ---- TOKEN ---- */
                    .withOp(new RandomToken(tokens, accounts, accounts), intPropOrElse("randomToken.bias", 0, props))
                    .withOp(
                            new RandomTokenAssociation(tokens, accounts, tokenRels, emptyCustomOutcomes)
                                    .ceiling(intPropOrElse(
                                            "randomTokenAssociation.ceilingNum",
                                            RandomTokenAssociation.DEFAULT_CEILING_NUM,
                                            props)),
                            intPropOrElse("randomTokenAssociation.bias", 0, props))
                    .withOp(
                            new RandomTokenDissociation(tokenRels, emptyCustomOutcomes),
                            intPropOrElse("randomTokenDissociation.bias", 0, props))
                    .withOp(
                            new RandomTokenDeletion(tokenRels, emptyCustomOutcomes),
                            intPropOrElse("randomTokenDeletion.bias", 0, props))
                    .withOp(
                            new RandomTokenTransfer(tokenRels, emptyCustomOutcomes),
                            intPropOrElse("randomTokenTransfer.bias", 0, props))
                    .withOp(
                            new RandomTokenFreeze(tokenRels, emptyCustomOutcomes),
                            intPropOrElse("randomTokenFreeze.bias", 0, props))
                    .withOp(
                            new RandomTokenUnfreeze(tokenRels, emptyCustomOutcomes),
                            intPropOrElse("randomTokenUnfreeze.bias", 0, props))
                    .withOp(
                            new RandomTokenKycGrant(tokenRels, emptyCustomOutcomes),
                            intPropOrElse("randomTokenKycGrant.bias", 0, props))
                    .withOp(
                            new RandomTokenKycRevoke(tokenRels, emptyCustomOutcomes),
                            intPropOrElse("randomTokenKycRevoke.bias", 0, props))
                    .withOp(
                            new RandomTokenMint(tokenRels, emptyCustomOutcomes),
                            intPropOrElse("randomTokenMint.bias", 0, props))
                    .withOp(
                            new RandomTokenBurn(tokenRels, emptyCustomOutcomes),
                            intPropOrElse("randomTokenBurn.bias", 0, props))
                    .withOp(
                            new RandomTokenUpdate(keys, tokens, accounts),
                            intPropOrElse("randomTokenUpdate.bias", 0, props))
                    .withOp(
                            new RandomTokenAccountWipe(tokenRels, emptyCustomOutcomes),
                            intPropOrElse("randomTokenAccountWipe.bias", 0, props))
                    //                    /* ---- CONTRACT ---- */
                    .withOp(
                            new RandomContract(keys, contracts)
                                    .ceiling(intPropOrElse(
                                            "randomContract.ceilingNum", RandomContract.DEFAULT_CEILING_NUM, props)),
                            intPropOrElse("randomContract.bias", 0, props))
                    .withOp(new RandomCall(calls, emptyCustomOutcomes), intPropOrElse("randomCall.bias", 0, props))
                    .withOp(
                            new RandomCallLocal(localCalls, emptyCustomOutcomes),
                            intPropOrElse("randomCallLocal.bias", 0, props))
                    .withOp(
                            new RandomContractDeletion(accounts, contracts, emptyCustomOutcomes),
                            intPropOrElse("randomContractDeletion.bias", 0, props))
                    .withOp(
                            new RandomSchedule(allSchedules, accounts)
                                    .ceiling(intPropOrElse(
                                            "randomSchedule.ceilingNum", RandomSchedule.DEFAULT_CEILING_NUM, props)),
                            intPropOrElse("randomSchedule.bias", 0, props))
                    .withOp(
                            new RandomScheduleDeletion(allSchedules, accounts),
                            intPropOrElse("randomScheduleDelete.bias", 0, props))
                    .withOp(
                            new RandomScheduleSign(allSchedules, accounts),
                            intPropOrElse("randomScheduleSign.bias", 0, props));
        };
    }

    private static HapiSpecOperation generateHollowAccount() {
        return withOpContext((spec, opLog) -> {
            final var ecdsaKey =
                    spec.registry().getKey(HOLLOW_ACCOUNT).getECDSASecp256K1().toByteArray();
            final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
            final var op = cryptoTransfer(tinyBarsFromTo(GENESIS, evmAddress, ONE_HUNDRED_HBARS))
                    .hasKnownStatusFrom(SUCCESS)
                    .via("txn" + HOLLOW_ACCOUNT);

            final HapiGetTxnRecord hapiGetTxnRecord =
                    getTxnRecord("txn" + HOLLOW_ACCOUNT).andAllChildRecords().assertingNothingAboutHashes();

            allRunFor(spec, op, hapiGetTxnRecord);

            if (!hapiGetTxnRecord.getChildRecords().isEmpty()) {
                final var newAccountID = hapiGetTxnRecord
                        .getFirstNonStakingChildRecord()
                        .getReceipt()
                        .getAccountID();
                spec.registry().saveAccountId(HOLLOW_ACCOUNT, newAccountID);
            }
        });
    }

    private static HapiSpecOperation completeHollowAccount() {
        return withOpContext((spec, opLog) -> {
            final var completion = cryptoTransfer(tinyBarsFromTo(LAZY_CREATE_SPONSOR, CRYPTO_TRANSFER_RECEIVER, 1))
                    .payingWith(HOLLOW_ACCOUNT)
                    .sigMapPrefixes(uniqueWithFullPrefixesFor(HOLLOW_ACCOUNT))
                    .hasKnownStatusFrom(SUCCESS);
            allRunFor(spec, completion);
        });
    }
}
