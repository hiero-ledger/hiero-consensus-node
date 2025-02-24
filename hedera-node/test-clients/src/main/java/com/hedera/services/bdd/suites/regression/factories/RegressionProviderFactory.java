// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.factories;

import static com.hedera.services.bdd.spec.infrastructure.OpProvider.UNIQUE_PAYER_ACCOUNT;
import static com.hedera.services.bdd.spec.infrastructure.OpProvider.UNIQUE_PAYER_ACCOUNT_INITIAL_BALANCE;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;

import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
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
import com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.RandomAccount;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.RandomAccountDeletion;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.RandomAccountInfo;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.RandomAccountRecords;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.RandomAccountUpdate;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.RandomTransfer;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.files.RandomAppend;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.files.RandomContents;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.files.RandomFile;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.files.RandomFileDeletion;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.files.RandomFileInfo;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.files.RandomFileUpdate;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.inventory.KeyInventoryCreation;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.meta.RandomReceipt;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.meta.RandomRecord;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.schedule.RandomSchedule;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.schedule.RandomScheduleDeletion;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.schedule.RandomScheduleInfo;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.schedule.RandomScheduleSign;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomToken;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenAccountWipe;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenAssociation;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenBurn;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenDeletion;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenDissociation;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenFreeze;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenInfo;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenKycGrant;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenKycRevoke;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenMint;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenTransfer;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenUnfreeze;
import com.hedera.services.bdd.spec.infrastructure.selectors.RandomSelector;
import com.hedera.services.bdd.spec.props.JutilPropertySource;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TopicID;
import java.io.File;
import java.util.function.Function;
import java.util.function.Supplier;

public class RegressionProviderFactory {
    public static final String RESOURCE_DIR = "eet-config";

    static HapiPropertySource propsFrom(final String resource) {
        final var loc = RESOURCE_DIR + File.separator + resource;
        return new JutilPropertySource(loc);
    }

    public static Function<HapiSpec, OpProvider> factoryFrom(Supplier<String> resource) {
        return spec -> {
            HapiPropertySource props = propsFrom(resource.get());

            var keys = new RegistrySourcedNameProvider<>(Key.class, spec.registry(), new RandomSelector());
            var files = new RegistrySourcedNameProvider<>(FileID.class, spec.registry(), new RandomSelector());
            var tokens = new RegistrySourcedNameProvider<>(TokenID.class, spec.registry(), new RandomSelector());
            var tokenRels = new RegistrySourcedNameProvider<>(
                    TokenAccountRegistryRel.class, spec.registry(), new RandomSelector());
            var allAccounts = new RegistrySourcedNameProvider<>(AccountID.class, spec.registry(), new RandomSelector());
            var tokenAdminAccounts = new RegistrySourcedNameProvider<>(
                    AccountID.class, spec.registry(), new RandomSelector(account -> !account.startsWith("stable-")));
            var contracts = new RegistrySourcedNameProvider<>(ContractID.class, spec.registry(), new RandomSelector());
            var calls = new RegistrySourcedNameProvider<>(
                    ActionableContractCall.class, spec.registry(), new RandomSelector());
            var localCalls = new RegistrySourcedNameProvider<>(
                    ActionableContractCallLocal.class, spec.registry(), new RandomSelector());
            var allTopics = new RegistrySourcedNameProvider<>(TopicID.class, spec.registry(), new RandomSelector());
            var unstableTopics = new RegistrySourcedNameProvider<>(
                    TopicID.class, spec.registry(), new RandomSelector(topic -> !topic.startsWith("stable-")));
            var allSchedules =
                    new RegistrySourcedNameProvider<>(ScheduleID.class, spec.registry(), new RandomSelector());

            final var customOutcomes = new ResponseCodeEnum[] {INVALID_SIGNATURE};
            KeyInventoryCreation keyInventory = new KeyInventoryCreation();

            return new BiasedDelegatingProvider()
                    /* --- <inventory> --- */
                    .withInitialization(flattened(
                            cryptoCreate(UNIQUE_PAYER_ACCOUNT)
                                    .balance(UNIQUE_PAYER_ACCOUNT_INITIAL_BALANCE)
                                    .withRecharging(),
                            keyInventory.creationOps()))
                    /* ----- META ----- */
                    .withOp(new RandomRecord(spec.txns()), intPropOrElse("randomRecord.bias", 0, props))
                    .withOp(new RandomReceipt(spec.txns()), intPropOrElse("randomReceipt.bias", 0, props))
                    /* ----- CRYPTO ----- */
                    .withOp(
                            new RandomAccount(keys, allAccounts)
                                    .ceiling(intPropOrElse(
                                                    "randomAccount.ceilingNum",
                                                    RandomAccount.DEFAULT_CEILING_NUM,
                                                    props)
                                            + intPropOrElse(
                                                    "randomTransfer.numStableAccounts",
                                                    RandomTransfer.DEFAULT_NUM_STABLE_ACCOUNTS,
                                                    props)),
                            intPropOrElse("randomAccount.bias", 0, props))
                    .withOp(
                            new RandomTransfer(allAccounts, customOutcomes)
                                    .numStableAccounts(intPropOrElse(
                                            "randomTransfer.numStableAccounts",
                                            RandomTransfer.DEFAULT_NUM_STABLE_ACCOUNTS,
                                            props))
                                    .recordProbability(doublePropOrElse(
                                            "randomTransfer.recordProbability",
                                            RandomTransfer.DEFAULT_RECORD_PROBABILITY,
                                            props)),
                            intPropOrElse("randomTransfer.bias", 0, props))
                    .withOp(
                            new RandomAccountUpdate(keys, allAccounts),
                            intPropOrElse("randomAccountUpdate.bias", 0, props))
                    .withOp(
                            new RandomAccountDeletion(allAccounts, customOutcomes),
                            intPropOrElse("randomAccountDeletion.bias", 0, props))
                    .withOp(new RandomAccountInfo(allAccounts), intPropOrElse("randomAccountInfo.bias", 0, props))
                    .withOp(new RandomAccountRecords(allAccounts), intPropOrElse("randomAccountRecords.bias", 0, props))
                    /* ---- CONSENSUS ---- */
                    .withOp(
                            new RandomTopicCreation(keys, allTopics, customOutcomes)
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
                            new RandomTopicDeletion(unstableTopics, customOutcomes),
                            intPropOrElse("randomTopicDeletion.bias", 0, props))
                    .withOp(
                            new RandomTopicUpdate(unstableTopics, customOutcomes),
                            intPropOrElse("randomTopicUpdate.bias", 0, props))
                    .withOp(
                            new RandomMessageSubmit(allTopics)
                                    .numStableTopics(intPropOrElse(
                                            "randomMessageSubmit.numStableTopics",
                                            RandomMessageSubmit.DEFAULT_NUM_STABLE_TOPICS,
                                            props)),
                            intPropOrElse("randomMessageSubmit.bias", 0, props))
                    .withOp(new RandomTopicInfo(allTopics), intPropOrElse("randomTopicInfo.bias", 0, props))
                    /* ---- FILE ---- */
                    .withOp(
                            new RandomFile(files)
                                    .ceiling(intPropOrElse(
                                            "randomFile.ceilingNum", RandomFile.DEFAULT_CEILING_NUM, props)),
                            intPropOrElse("randomFile.bias", 0, props))
                    .withOp(new RandomFileDeletion(files), intPropOrElse("randomFileDeletion.bias", 0, props))
                    .withOp(new RandomFileUpdate(files), intPropOrElse("randomFileUpdate.bias", 0, props))
                    .withOp(new RandomAppend(files), intPropOrElse("randomAppend.bias", 0, props))
                    .withOp(new RandomFileInfo(files), intPropOrElse("randomFileInfo.bias", 0, props))
                    .withOp(new RandomContents(files), intPropOrElse("randomContents.bias", 0, props))
                    /* ---- TOKEN ---- */
                    .withOp(
                            new RandomToken(tokens, allAccounts, tokenAdminAccounts),
                            intPropOrElse("randomToken.bias", 0, props))
                    .withOp(
                            new RandomTokenAssociation(tokens, tokenAdminAccounts, tokenRels, customOutcomes)
                                    .ceiling(intPropOrElse(
                                            "randomTokenAssociation.ceilingNum",
                                            RandomTokenAssociation.DEFAULT_CEILING_NUM,
                                            props)),
                            intPropOrElse("randomTokenAssociation.bias", 0, props))
                    .withOp(
                            new RandomTokenDissociation(tokenRels, customOutcomes),
                            intPropOrElse("randomTokenDissociation.bias", 0, props))
                    .withOp(
                            new RandomTokenDeletion(tokenRels, customOutcomes),
                            intPropOrElse("randomTokenDeletion.bias", 0, props))
                    .withOp(
                            new RandomTokenTransfer(tokenRels, customOutcomes),
                            intPropOrElse("randomTokenTransfer.bias", 0, props))
                    .withOp(
                            new RandomTokenFreeze(tokenRels, customOutcomes),
                            intPropOrElse("randomTokenFreeze.bias", 0, props))
                    .withOp(
                            new RandomTokenUnfreeze(tokenRels, customOutcomes),
                            intPropOrElse("randomTokenUnfreeze.bias", 0, props))
                    .withOp(
                            new RandomTokenKycGrant(tokenRels, customOutcomes),
                            intPropOrElse("randomTokenKycGrant.bias", 0, props))
                    .withOp(
                            new RandomTokenKycRevoke(tokenRels, customOutcomes),
                            intPropOrElse("randomTokenKycRevoke.bias", 0, props))
                    .withOp(
                            new RandomTokenMint(tokenRels, customOutcomes),
                            intPropOrElse("randomTokenMint.bias", 0, props))
                    .withOp(
                            new RandomTokenBurn(tokenRels, customOutcomes),
                            intPropOrElse("randomTokenBurn.bias", 0, props))
                    // bug
                    //                    .withOp(
                    //                            new RandomTokenUpdate(keys, tokens, allAccounts),
                    //                            intPropOrElse("randomTokenUpdate.bias", 0, props))
                    .withOp(
                            new RandomTokenAccountWipe(tokenRels, customOutcomes),
                            intPropOrElse("randomTokenAccountWipe.bias", 0, props))
                    .withOp(new RandomTokenInfo(tokens), intPropOrElse("randomTokenInfo.bias", 0, props))
                    /* ---- CONTRACT ---- */
                    .withOp(new RandomCall(calls, customOutcomes), intPropOrElse("randomCall.bias", 0, props))
                    .withOp(
                            new RandomCallLocal(localCalls, customOutcomes),
                            intPropOrElse("randomCallLocal.bias", 0, props))
                    .withOp(
                            new RandomContractDeletion(allAccounts, contracts, customOutcomes),
                            intPropOrElse("randomContractDeletion.bias", 0, props))
                    .withOp(
                            new RandomContract(keys, contracts)
                                    .ceiling(intPropOrElse(
                                            "randomContract.ceilingNum", RandomContract.DEFAULT_CEILING_NUM, props)),
                            intPropOrElse("randomContract.bias", 0, props))
                    .withOp(
                            new RandomSchedule(allSchedules, allAccounts)
                                    .ceiling(intPropOrElse(
                                            "randomSchedule.ceilingNum", RandomSchedule.DEFAULT_CEILING_NUM, props)),
                            intPropOrElse("randomSchedule.bias", 0, props))
                    .withOp(new RandomScheduleInfo(allSchedules), intPropOrElse("randomScheduleInfo.bias", 0, props))
                    .withOp(
                            new RandomScheduleDeletion(allSchedules, allAccounts),
                            intPropOrElse("randomScheduleDelete.bias", 0, props))
                    .withOp(
                            new RandomScheduleSign(allSchedules, allAccounts),
                            intPropOrElse("randomScheduleSign.bias", 0, props));
        };
    }

    private static double doublePropOrElse(String name, double defaultValue, HapiPropertySource props) {
        return props.has(name) ? props.getDouble(name) : defaultValue;
    }

    static int intPropOrElse(String name, int defaultValue, HapiPropertySource props) {
        return props.has(name) ? props.getInteger(name) : defaultValue;
    }
}
