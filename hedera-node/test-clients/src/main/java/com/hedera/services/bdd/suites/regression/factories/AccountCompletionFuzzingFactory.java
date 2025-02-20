// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.factories;

import static com.hedera.services.bdd.spec.infrastructure.OpProvider.UNIQUE_PAYER_ACCOUNT;
import static com.hedera.services.bdd.spec.infrastructure.OpProvider.UNIQUE_PAYER_ACCOUNT_INITIAL_BALANCE;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.CRYPTO_TRANSFER_RECEIVER;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.LAZY_CREATE_SPONSOR;
import static com.hedera.services.bdd.suites.regression.factories.IdFuzzingProviderFactory.onlyEcdsaKeys;
import static com.hedera.services.bdd.suites.regression.factories.RegressionProviderFactory.intPropOrElse;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.BiasedDelegatingProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.RandomAccount;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.hollow.RandomContractCallSignedBy;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.hollow.RandomContractCreateSignedBy;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.hollow.RandomHollowAccount;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.hollow.RandomHollowAccountDeletion;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.hollow.RandomTokenAssociateSignedBy;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.hollow.RandomTransferSignedBy;
import com.hedera.services.bdd.spec.infrastructure.selectors.RandomSelector;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Key;
import java.util.function.Function;

public class AccountCompletionFuzzingFactory {
    public static final String CONTRACT = "PayReceivable";
    public static final String VANILLA_TOKEN = "TokenD";
    public static final String TOKEN_TREASURY = "treasury";

    private AccountCompletionFuzzingFactory() {
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
            tokenCreate(VANILLA_TOKEN).treasury(TOKEN_TREASURY),
            contractCreate(CONTRACT)
        };
    }

    /**
     * Random selection of operations, testing the completion of hollow accounts.
     * Operations are: creation of random keys, creation of hollow accounts from those keys and random operations signed by the keys
     * <br>
     * NOTE: When creating accounts they are saved in both the account and key registries.
     * To differentiate them from keys created for hollow accounts, we use {@link RandomHollowAccount#ACCOUNT_SUFFIX}
     *
     * @param resource config
     */
    public static Function<HapiSpec, OpProvider> hollowAccountFuzzingWith(final String resource) {
        return spec -> {
            final var props = RegressionProviderFactory.propsFrom(resource);

            final var accounts =
                    new RegistrySourcedNameProvider<>(AccountID.class, spec.registry(), new RandomSelector());

            final var keys = new RegistrySourcedNameProvider<>(Key.class, spec.registry(), new RandomSelector());

            return new BiasedDelegatingProvider()
                    .shouldLogNormalFlow(true)
                    .withInitialization(onlyEcdsaKeys())
                    .withOp(
                            new RandomHollowAccount(spec.registry(), keys, accounts)
                                    .ceiling(intPropOrElse(
                                            "randomAccount.ceilingNum", RandomAccount.DEFAULT_CEILING_NUM, props)),
                            intPropOrElse("randomAccount.bias", 0, props))
                    .withOp(
                            new RandomTransferSignedBy(spec.registry(), accounts),
                            intPropOrElse("randomTransfer.bias", 0, props))
                    .withOp(
                            new RandomTokenAssociateSignedBy(spec.registry(), accounts),
                            intPropOrElse("randomTokenAssociate.bias", 0, props))
                    .withOp(
                            new RandomContractCreateSignedBy(spec.registry(), accounts),
                            intPropOrElse("randomContractCreate.bias", 0, props))
                    .withOp(
                            new RandomContractCallSignedBy(spec.registry(), accounts),
                            intPropOrElse("randomContractCall.bias", 0, props))
                    .withOp(
                            new RandomHollowAccountDeletion(accounts),
                            intPropOrElse("randomAccountDeletion.bias", 0, props));
        };
    }
}
