// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.factories;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.spec.infrastructure.OpProvider.STANDARD_PERMISSIBLE_OUTCOMES;
import static com.hedera.services.bdd.spec.infrastructure.OpProvider.UNIQUE_PAYER_ACCOUNT;
import static com.hedera.services.bdd.spec.infrastructure.OpProvider.UNIQUE_PAYER_ACCOUNT_INITIAL_BALANCE;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.LAZY_CREATE_SPONSOR;
import static com.hedera.services.bdd.suites.regression.factories.RegressionProviderFactory.intPropOrElse;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.listeners.TokenAccountRegistryRel;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.BiasedDelegatingProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.contract.RandomContract;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.contract.RandomContractDeletion;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.RandomAccountDeletionWithReceiver;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.RandomTransferFromSigner;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.RandomTransferToSigner;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.hollow.RandomAccountUpdateHollowAccount;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.hollow.RandomTokenAssociationHollowAccount;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.hollow.RandomTokenHollowAccount;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomToken;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.token.RandomTokenAssociation;
import com.hedera.services.bdd.spec.infrastructure.selectors.RandomSelector;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenID;
import java.util.function.Function;

public class HollowAccountFuzzingFactory {
    public static final String HOLLOW_ACCOUNT = "hollowAccount";

    private static final ResponseCodeEnum[] hollowAccountOutcomes = {INVALID_SIGNATURE};

    private HollowAccountFuzzingFactory() {
        throw new IllegalStateException("Static factory class");
    }

    public static HapiSpecOperation[] initOperations() {
        return new HapiSpecOperation[] {
            cryptoCreate(UNIQUE_PAYER_ACCOUNT)
                    .balance(UNIQUE_PAYER_ACCOUNT_INITIAL_BALANCE)
                    .withRecharging(),
            cryptoCreate(LAZY_CREATE_SPONSOR)
                    .balance(UNIQUE_PAYER_ACCOUNT_INITIAL_BALANCE)
                    .withRecharging()
        };
    }

    public static Function<HapiSpec, OpProvider> hollowAccountFuzzingTest(final String resource) {
        return spec -> {
            final var props = RegressionProviderFactory.propsFrom(resource);

            final var hollowAccounts = new RegistrySourcedNameProvider<>(
                    AccountID.class, spec.registry(), new RandomSelector(account -> account.equals(HOLLOW_ACCOUNT)));
            final var validAccounts = new RegistrySourcedNameProvider<>(
                    AccountID.class,
                    spec.registry(),
                    new RandomSelector(account -> account.equals(UNIQUE_PAYER_ACCOUNT)));
            final var accountsToDelete = new RegistrySourcedNameProvider<>(
                    AccountID.class,
                    spec.registry(),
                    new RandomSelector(account -> account.equals(LAZY_CREATE_SPONSOR)));

            final var tokens = new RegistrySourcedNameProvider<>(TokenID.class, spec.registry(), new RandomSelector());
            var tokenRels = new RegistrySourcedNameProvider<>(
                    TokenAccountRegistryRel.class, spec.registry(), new RandomSelector());
            final var keys = new RegistrySourcedNameProvider<>(Key.class, spec.registry(), new RandomSelector());
            var contracts = new RegistrySourcedNameProvider<>(ContractID.class, spec.registry(), new RandomSelector());
            final var emptyCustomOutcomes = new ResponseCodeEnum[] {};

            return new BiasedDelegatingProvider()
                    .shouldLogNormalFlow(true)
                    .withInitialization(
                            newKeyNamed(HOLLOW_ACCOUNT).shape(SigControl.SECP256K1_ON), generateHollowAccount())
                    /* ---- TRANSFER ---- */
                    // expects invalid signature
                    .withOp(
                            new RandomTransferToSigner(hollowAccounts, UNIQUE_PAYER_ACCOUNT, hollowAccountOutcomes),
                            intPropOrElse("randomTransferFromHollowAccount.bias", 0, props))
                    // expects success
                    .withOp(
                            new RandomTransferFromSigner(
                                    hollowAccounts, UNIQUE_PAYER_ACCOUNT, STANDARD_PERMISSIBLE_OUTCOMES),
                            intPropOrElse("randomTransfer.bias", 0, props))

                    /* ---- TOKEN ---- */
                    // This token create op uses normal accounts to create the tokens because token create will
                    // fail if we use hollow account and not set any tokens in registry but we need them for association
                    // expects success
                    .withOp(
                            new RandomToken(tokens, validAccounts, validAccounts),
                            intPropOrElse("randomToken.bias", 0, props))
                    // expects invalid signature
                    .withOp(
                            new RandomTokenHollowAccount(tokens, hollowAccounts, UNIQUE_PAYER_ACCOUNT),
                            intPropOrElse("randomTokenHollow.bias", 0, props))
                    // expects invalid signature
                    .withOp(
                            new RandomTokenAssociationHollowAccount(
                                            tokens, hollowAccounts, tokenRels, UNIQUE_PAYER_ACCOUNT)
                                    .ceiling(intPropOrElse(
                                            "randomTokenAssociation.ceilingNum",
                                            RandomTokenAssociation.DEFAULT_CEILING_NUM,
                                            props)),
                            intPropOrElse("randomTokenAssociation.bias", 0, props))
                    /* ---- ACCOUNT ---- */
                    // expects invalid signature
                    .withOp(
                            new RandomAccountUpdateHollowAccount(keys, hollowAccounts, UNIQUE_PAYER_ACCOUNT),
                            intPropOrElse("randomAccountUpdate.bias", 0, props))
                    .withOp(
                            new RandomAccountDeletionWithReceiver(hollowAccounts, accountsToDelete),
                            intPropOrElse("randomAccountDeletion.bias", 0, props))
                    /* ---- CONTRACT ---- */
                    // expects success
                    .withOp(
                            new RandomContract(keys, contracts)
                                    .ceiling(intPropOrElse(
                                            "randomContract.ceilingNum", RandomContract.DEFAULT_CEILING_NUM, props)),
                            intPropOrElse("randomContract.bias", 0, props))
                    .withOp(
                            new RandomContractDeletion(hollowAccounts, contracts, emptyCustomOutcomes),
                            intPropOrElse("randomContractDeletion.bias", 0, props));
        };
    }

    public static HapiSpecOperation generateHollowAccount() {
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
}
