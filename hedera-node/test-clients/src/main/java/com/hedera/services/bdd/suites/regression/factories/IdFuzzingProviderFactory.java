// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.factories;

import static com.hedera.services.bdd.spec.infrastructure.OpProvider.UNIQUE_PAYER_ACCOUNT;
import static com.hedera.services.bdd.spec.infrastructure.OpProvider.UNIQUE_PAYER_ACCOUNT_INITIAL_BALANCE;
import static com.hedera.services.bdd.spec.infrastructure.meta.InitialAccountIdentifiers.KEY_FOR_INCONGRUENT_ALIAS;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.crypto.LeakyCryptoTestsSuite.AUTO_ACCOUNT;
import static com.hedera.services.bdd.suites.regression.factories.RegressionProviderFactory.intPropOrElse;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.meta.InitialAccountIdentifiers;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.BiasedDelegatingProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.EthereumTransferToRandomEVMAddress;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.RandomAccount;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.RandomAccountUpdate;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.TransferToRandomEVMAddress;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.crypto.TransferToRandomKey;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.inventory.KeyInventoryCreation;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.precompile.RandomERC20TransferLazyCreate;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.precompile.RandomERC721TransferLazyCreate;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.precompile.RandomFungibleTransferLazyCreate;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.precompile.RandomHbarTransferLazyCreate;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.precompile.RandomNonFungibleTransferLazyCreate;
import com.hedera.services.bdd.spec.infrastructure.selectors.RandomSelector;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class IdFuzzingProviderFactory {

    public static final long INITIAL_SUPPLY = 1_000_000_000L;

    public static final String FUNGIBLE_TOKEN = "fungibleToken";
    public static final String ERC_FUNGIBLE_TOKEN = "ercFungibleToken";
    public static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";
    public static final String ERC_NON_FUNGIBLE_TOKEN = "ercNonFungibleToken";
    public static final String MULTI_KEY = "purpose";
    public static final String OWNER = "owner";
    public static final String SENDER = "sender";
    public static final String TRANSFER_TO_ALIAS_PRECOMPILE_CONTRACT = "PrecompileAliasXfer";
    public static final String NESTED_LAZY_PRECOMPILE_CONTRACT = "LazyPrecompileTransfersAtomic";
    public static final String TOKEN_TREASURY = "treasury";
    public static final String TOKEN_TREASURY_ERC = "treasuryErc";
    public static final String ECDSA_KEY = "abcdECDSAkey";
    public static final String TRANSFER_TOKEN_TXN = "transferTokenTxn";
    public static final String TRANSFER_NFT_TXN = "transferNFTTxn";
    public static final String ERC_20_CONTRACT = "ERC20Contract";
    public static final String ERC_721_CONTRACT = "ERC721Contract";
    private static final String BASE_APPROVE_TXN = "baseApproveTxn";
    private static final String SPENDER = "spender";
    /**
     * How many different ECDSA keys we will re-use as we continually create, update, and delete
     * accounts with random {@link InitialAccountIdentifiers} based on this fixed set of keys.
     */
    private static final int NUM_DISTINCT_ECDSA_KEYS = 42;

    private static final String RANDOM_TRANSFER_BIAS = "randomTransfer.bias";

    private IdFuzzingProviderFactory() {}

    public static HapiSpecOperation[] initOperations() {
        return Stream.of(
                        initOpCommon(),
                        initOpHbarTransfer(),
                        initOpFungibleTransfer(),
                        initOpNonFungibleTransfer(),
                        initOpERC20Transfer(),
                        initOpERC721Transfer())
                .flatMap(Stream::of)
                .toArray(HapiSpecOperation[]::new);
    }

    public static Function<HapiSpec, OpProvider> idFuzzingWith(final String resource) {
        return spec -> {
            final var props = RegressionProviderFactory.propsFrom(resource);

            final var keys = new RegistrySourcedNameProvider<>(Key.class, spec.registry(), new RandomSelector());
            final var accounts =
                    new RegistrySourcedNameProvider<>(AccountID.class, spec.registry(), new RandomSelector());

            return new BiasedDelegatingProvider()
                    /* --- <inventory> --- */
                    .withInitialization(onlyEcdsaKeys())
                    /* ----- CRYPTO ----- */
                    .withOp(
                            new RandomAccount(keys, accounts, true)
                                    .ceiling(intPropOrElse(
                                            "randomAccount.ceilingNum", RandomAccount.DEFAULT_CEILING_NUM, props)),
                            intPropOrElse("randomAccount.bias", 0, props))
                    .withOp(new TransferToRandomEVMAddress(keys), intPropOrElse(RANDOM_TRANSFER_BIAS, 0, props))
                    .withOp(new TransferToRandomKey(keys), intPropOrElse(RANDOM_TRANSFER_BIAS, 0, props))
                    .withOp(
                            new RandomAccountUpdate(keys, accounts),
                            intPropOrElse("randomAccountUpdate.bias", 0, props))
                    .withOp(
                            new EthereumTransferToRandomEVMAddress(spec.registry(), keys),
                            intPropOrElse("randomEthereumTransactionTransfer.bias", 0, props))
                    .withOp(
                            new RandomHbarTransferLazyCreate(spec.registry(), keys),
                            intPropOrElse("randomHbar.bias", 0, props))
                    .withOp(
                            new RandomNonFungibleTransferLazyCreate(spec.registry(), keys),
                            intPropOrElse("randomFungibleTransfer.bias", 0, props))
                    .withOp(
                            new RandomFungibleTransferLazyCreate(spec.registry(), keys),
                            intPropOrElse("randomFungibleTransfer.bias", 0, props))
                    .withOp(
                            new RandomERC20TransferLazyCreate(spec.registry(), keys),
                            intPropOrElse("randomERC20Transfer.bias", 0, props))
                    .withOp(
                            new RandomERC721TransferLazyCreate(spec.registry(), keys),
                            intPropOrElse("randomERC721Transfer.bias", 0, props));
        };
    }

    public static Function<HapiSpec, OpProvider> idTransferToRandomKeyWith(final String resource) {
        return spec -> {
            final var props = RegressionProviderFactory.propsFrom(resource);

            final var keys = new RegistrySourcedNameProvider<>(Key.class, spec.registry(), new RandomSelector());
            KeyInventoryCreation keyInventory = new KeyInventoryCreation();

            return new BiasedDelegatingProvider()
                    /* --- <inventory> --- */
                    .withInitialization(keyInventory.creationOps())
                    /* ----- CRYPTO ----- */
                    .withOp(new TransferToRandomKey(keys), intPropOrElse(RANDOM_TRANSFER_BIAS, 0, props));
        };
    }

    private static HapiSpecOperation[] initOpCommon() {
        return new HapiSpecOperation[] {
            // common init
            newKeyNamed(KEY_FOR_INCONGRUENT_ALIAS).shape(SECP_256K1_SHAPE),
            newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
            cryptoCreate(UNIQUE_PAYER_ACCOUNT)
                    .balance(UNIQUE_PAYER_ACCOUNT_INITIAL_BALANCE)
                    .withRecharging(),
            cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS).withRecharging(),
            cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                    .via(AUTO_ACCOUNT),
            newKeyNamed(ECDSA_KEY).shape(SECP_256K1_SHAPE),
            newKeyNamed(MULTI_KEY),
            cryptoCreate(TOKEN_TREASURY),
            cryptoCreate(OWNER).balance(100 * ONE_HUNDRED_HBARS),
            cryptoCreate(SPENDER)
        };
    }

    private static HapiSpecOperation[] initOpHbarTransfer() {
        return new HapiSpecOperation[] {
            cryptoCreate(SENDER).balance(INITIAL_SUPPLY).key(MULTI_KEY).maxAutomaticTokenAssociations(5),
            uploadInitCode(NESTED_LAZY_PRECOMPILE_CONTRACT),
            contractCreate(NESTED_LAZY_PRECOMPILE_CONTRACT).gas(3_000_000),
        };
    }

    private static HapiSpecOperation[] initOpFungibleTransfer() {
        final AtomicReference<String> tokenAddr = new AtomicReference<>();

        return new HapiSpecOperation[] {
            tokenCreate(FUNGIBLE_TOKEN)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .initialSupply(INITIAL_SUPPLY)
                    .treasury(TOKEN_TREASURY)
                    .adminKey(MULTI_KEY)
                    .supplyKey(MULTI_KEY)
                    .exposingCreatedIdTo(id ->
                            tokenAddr.set(HapiPropertySource.asHexedSolidityAddress(HapiPropertySource.asToken(id)))),
            uploadInitCode(TRANSFER_TO_ALIAS_PRECOMPILE_CONTRACT),
            contractCreate(TRANSFER_TO_ALIAS_PRECOMPILE_CONTRACT).gas(3_000_000L),
            tokenAssociate(OWNER, List.of(FUNGIBLE_TOKEN)),
            cryptoTransfer(moving(INITIAL_SUPPLY, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, OWNER))
        };
    }

    private static HapiSpecOperation[] initOpNonFungibleTransfer() {
        return new HapiSpecOperation[] {
            tokenCreate(NON_FUNGIBLE_TOKEN)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .initialSupply(0)
                    .treasury(TOKEN_TREASURY)
                    .adminKey(MULTI_KEY)
                    .supplyKey(MULTI_KEY),
            tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
            tokenAssociate(SPENDER, NON_FUNGIBLE_TOKEN),
            tokenAssociate(TRANSFER_TO_ALIAS_PRECOMPILE_CONTRACT, NON_FUNGIBLE_TOKEN),
            mintToken(NON_FUNGIBLE_TOKEN, erc721UniqueTokens()),
            cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L)
                    .between(TOKEN_TREASURY, OWNER))
        };
    }

    private static HapiSpecOperation[] initOpERC20Transfer() {
        final AtomicReference<String> tokenAddr = new AtomicReference<>();

        return new HapiSpecOperation[] {
            cryptoCreate(TOKEN_TREASURY_ERC),
            tokenCreate(ERC_FUNGIBLE_TOKEN)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .initialSupply(INITIAL_SUPPLY)
                    .treasury(TOKEN_TREASURY_ERC)
                    .adminKey(MULTI_KEY)
                    .supplyKey(MULTI_KEY)
                    .exposingCreatedIdTo(id ->
                            tokenAddr.set(HapiPropertySource.asHexedSolidityAddress(HapiPropertySource.asToken(id)))),
            uploadInitCode(ERC_20_CONTRACT),
            contractCreate(ERC_20_CONTRACT),
            tokenAssociate(ERC_20_CONTRACT, List.of(ERC_FUNGIBLE_TOKEN)),
            cryptoTransfer(moving(INITIAL_SUPPLY, ERC_FUNGIBLE_TOKEN).between(TOKEN_TREASURY_ERC, ERC_20_CONTRACT))
        };
    }

    private static HapiSpecOperation[] initOpERC721Transfer() {
        return new HapiSpecOperation[] {
            tokenCreate(ERC_NON_FUNGIBLE_TOKEN)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .initialSupply(0)
                    .treasury(TOKEN_TREASURY)
                    .adminKey(MULTI_KEY)
                    .supplyKey(MULTI_KEY),
            uploadInitCode(ERC_721_CONTRACT),
            contractCreate(ERC_721_CONTRACT).gas(3_000_000L),
            tokenAssociate(OWNER, ERC_NON_FUNGIBLE_TOKEN),
            tokenAssociate(SPENDER, ERC_NON_FUNGIBLE_TOKEN),
            tokenAssociate(ERC_721_CONTRACT, ERC_NON_FUNGIBLE_TOKEN),
            mintToken(ERC_NON_FUNGIBLE_TOKEN, erc721UniqueTokens()),
            cryptoTransfer(movingUnique(ERC_NON_FUNGIBLE_TOKEN, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L)
                    .between(TOKEN_TREASURY, OWNER)),
            cryptoApproveAllowance()
                    .payingWith(UNIQUE_PAYER_ACCOUNT)
                    .addNftAllowance(
                            OWNER,
                            ERC_NON_FUNGIBLE_TOKEN,
                            ERC_721_CONTRACT,
                            false,
                            List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L))
                    .via(BASE_APPROVE_TXN)
                    .logged()
                    .signedBy(UNIQUE_PAYER_ACCOUNT, OWNER)
                    .fee(ONE_HBAR)
        };
    }

    private static List<ByteString> erc721UniqueTokens() {
        return List.of(
                ByteString.copyFromUtf8("a"),
                ByteString.copyFromUtf8("b"),
                ByteString.copyFromUtf8("c"),
                ByteString.copyFromUtf8("d"),
                ByteString.copyFromUtf8("e"),
                ByteString.copyFromUtf8("f"),
                ByteString.copyFromUtf8("g"),
                ByteString.copyFromUtf8("h"),
                ByteString.copyFromUtf8("i"),
                ByteString.copyFromUtf8("j"));
    }

    public static HapiSpecOperation[] onlyEcdsaKeys() {
        return IntStream.range(0, NUM_DISTINCT_ECDSA_KEYS)
                .mapToObj(i -> newKeyNamed("Fuzz#" + i).shape(SigControl.SECP256K1_ON))
                .toArray(HapiSpecOperation[]::new);
    }
}
