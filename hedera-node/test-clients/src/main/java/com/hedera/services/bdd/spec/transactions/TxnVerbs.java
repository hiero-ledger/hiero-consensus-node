// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions;

import static com.hedera.services.bdd.spec.HapiPropertySource.explicitBytesOf;
import static com.hedera.services.bdd.spec.dsl.entities.SpecContract.VARIANT_NONE;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.randomUppercase;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate.WELL_KNOWN_INITIAL_SUPPLY;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate.WELL_KNOWN_NFT_SUPPLY_KEY;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.updateLargeFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.CONSTRUCTOR;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.defaultContractsRoot;
import static com.hedera.services.bdd.suites.contract.Utils.extractByteCode;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.Utils.getResourcePath;
import static org.apache.commons.lang3.StringUtils.EMPTY;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.google.protobuf.ByteString;
import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.queries.crypto.ReferenceType;
import com.hedera.services.bdd.spec.transactions.consensus.HapiMessageSubmit;
import com.hedera.services.bdd.spec.transactions.consensus.HapiTopicCreate;
import com.hedera.services.bdd.spec.transactions.consensus.HapiTopicDelete;
import com.hedera.services.bdd.spec.transactions.consensus.HapiTopicUpdate;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCall;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCreate;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractDelete;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractUpdate;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumContractCreate;
import com.hedera.services.bdd.spec.transactions.contract.HapiLambdaSStore;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoApproveAllowance;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoCreate;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoDelete;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoDeleteAllowance;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoUpdate;
import com.hedera.services.bdd.spec.transactions.file.HapiFileAppend;
import com.hedera.services.bdd.spec.transactions.file.HapiFileCreate;
import com.hedera.services.bdd.spec.transactions.file.HapiFileDelete;
import com.hedera.services.bdd.spec.transactions.file.HapiFileUpdate;
import com.hedera.services.bdd.spec.transactions.network.HapiUncheckedSubmit;
import com.hedera.services.bdd.spec.transactions.node.HapiNodeCreate;
import com.hedera.services.bdd.spec.transactions.node.HapiNodeDelete;
import com.hedera.services.bdd.spec.transactions.node.HapiNodeUpdate;
import com.hedera.services.bdd.spec.transactions.schedule.HapiScheduleCreate;
import com.hedera.services.bdd.spec.transactions.schedule.HapiScheduleDelete;
import com.hedera.services.bdd.spec.transactions.schedule.HapiScheduleSign;
import com.hedera.services.bdd.spec.transactions.system.HapiFreeze;
import com.hedera.services.bdd.spec.transactions.system.HapiStateSignature;
import com.hedera.services.bdd.spec.transactions.system.HapiSysDelete;
import com.hedera.services.bdd.spec.transactions.system.HapiSysUndelete;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenAirdrop;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenAssociate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenBurn;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCancelAirdrop;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenClaimAirdrop;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenDelete;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenDissociate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenFeeScheduleUpdate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenFreeze;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenKycGrant;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenKycRevoke;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenMint;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenPause;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenReject;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenUnfreeze;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenUnpause;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenUpdate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenUpdateNfts;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenWipe;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.transactions.util.HapiAtomicBatch;
import com.hedera.services.bdd.spec.transactions.util.HapiUtilPrng;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hederahashgraph.api.proto.java.ContractCreateTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;
import com.hederahashgraph.api.proto.java.EthereumTransactionBody;
import com.hederahashgraph.api.proto.java.PendingAirdropId;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenReference;
import com.hederahashgraph.api.proto.java.TokenType;
import com.hederahashgraph.api.proto.java.TopicID;
import com.hederahashgraph.api.proto.java.TransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public class TxnVerbs {
    /* CRYPTO */
    public static HapiCryptoCreate cryptoCreate(String account) {
        return new HapiCryptoCreate(account);
    }

    public static HapiCryptoDelete cryptoDelete(String account) {
        return new HapiCryptoDelete(account);
    }

    public static HapiCryptoDelete cryptoDeleteAliased(final String alias) {
        return new HapiCryptoDelete(alias, ReferenceType.ALIAS_KEY_NAME);
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    public static HapiCryptoTransfer sortedCryptoTransfer(Function<HapiSpec, TransferList>... providers) {
        return new HapiCryptoTransfer(true, providers);
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    public static HapiCryptoTransfer cryptoTransfer(Function<HapiSpec, TransferList>... providers) {
        return new HapiCryptoTransfer(providers);
    }

    public static HapiSpecOperation newAliasedAccount(@NonNull final String account) {
        final var creationTxn = "transfer" + randomUppercase(5);
        final var aliasKey = "receiverKey" + randomUppercase(5);
        return withOpContext((spec, opLog) -> {
            CustomSpecAssert.allRunFor(
                    spec,
                    newKeyNamed(aliasKey),
                    cryptoTransfer(tinyBarsFromAccountToAlias(DEFAULT_PAYER, aliasKey, ONE_HUNDRED_HBARS))
                            .via(creationTxn),
                    getTxnRecord(creationTxn).andAllChildRecords().exposingCreationsTo(creations -> {
                        final var createdId = HapiPropertySource.asAccount(creations.getFirst());
                        spec.registry().saveAccountId(account, createdId);
                        spec.registry().saveKey(account, spec.registry().getKey(aliasKey));
                    }));
        });
    }

    public static HapiCryptoTransfer cryptoTransfer(BiConsumer<HapiSpec, CryptoTransferTransactionBody.Builder> def) {
        return new HapiCryptoTransfer(def);
    }

    public static HapiCryptoTransfer cryptoTransfer(TokenMovement... sources) {
        return new HapiCryptoTransfer(sources);
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    public static HapiTokenCancelAirdrop tokenCancelAirdrop(
            final Function<HapiSpec, PendingAirdropId>... pendingAirdropIds) {
        return new HapiTokenCancelAirdrop(pendingAirdropIds);
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    public static HapiTokenClaimAirdrop tokenClaimAirdrop(Function<HapiSpec, PendingAirdropId>... pendingAirdropIds) {
        return new HapiTokenClaimAirdrop(pendingAirdropIds);
    }

    public static HapiCryptoUpdate cryptoUpdate(String account) {
        return new HapiCryptoUpdate(account);
    }

    public static HapiTokenAirdrop tokenAirdrop(TokenMovement... sources) {
        return new HapiTokenAirdrop(sources);
    }

    public static HapiCryptoUpdate cryptoUpdateAliased(final String alias) {
        return new HapiCryptoUpdate(alias, ReferenceType.ALIAS_KEY_NAME);
    }

    public static HapiCryptoApproveAllowance cryptoApproveAllowance() {
        return new HapiCryptoApproveAllowance();
    }

    public static HapiCryptoDeleteAllowance cryptoDeleteAllowance() {
        return new HapiCryptoDeleteAllowance();
    }

    /* CONSENSUS */
    public static HapiTopicCreate createTopic(String topic) {
        return new HapiTopicCreate(topic);
    }

    public static HapiTopicDelete deleteTopic(String topic) {
        return new HapiTopicDelete(topic);
    }

    public static HapiTopicDelete deleteTopic(Function<HapiSpec, TopicID> topicFn) {
        return new HapiTopicDelete(topicFn);
    }

    public static HapiTopicUpdate updateTopic(String topic) {
        return new HapiTopicUpdate(topic);
    }

    public static HapiMessageSubmit submitMessageTo(String topic) {
        return new HapiMessageSubmit(topic);
    }

    public static HapiMessageSubmit submitMessageTo(Function<HapiSpec, TopicID> topicFn) {
        return new HapiMessageSubmit(topicFn);
    }

    /* FILE */
    public static HapiFileCreate fileCreate(String fileName) {
        return new HapiFileCreate(fileName);
    }

    public static HapiFileAppend fileAppend(String fileName) {
        return new HapiFileAppend(fileName);
    }

    public static HapiFileUpdate fileUpdate(String fileName) {
        return new HapiFileUpdate(fileName);
    }

    public static HapiFileDelete fileDelete(String fileName) {
        return new HapiFileDelete(fileName);
    }

    public static HapiFileDelete fileDelete(Supplier<String> fileNameSupplier) {
        return new HapiFileDelete(fileNameSupplier);
    }

    /* NODE */
    public static HapiNodeCreate nodeCreate(String node) {
        return new HapiNodeCreate(node);
    }

    public static HapiNodeUpdate nodeUpdate(String node) {
        return new HapiNodeUpdate(node);
    }

    public static HapiNodeDelete nodeDelete(String node) {
        return new HapiNodeDelete(node);
    }

    /* TOKEN */
    public static HapiTokenDissociate tokenDissociate(String account, String... tokens) {
        return new HapiTokenDissociate(account, tokens);
    }

    public static HapiTokenDissociate tokenDissociateWithAlias(String alias, String... tokens) {
        return new HapiTokenDissociate(alias, ReferenceType.ALIAS_KEY_NAME, tokens);
    }

    public static HapiTokenAssociate tokenAssociate(String account, String... tokens) {
        return new HapiTokenAssociate(account, tokens);
    }

    public static HapiTokenAssociate tokenAssociateWithAlias(String alias, String... tokens) {
        return new HapiTokenAssociate(alias, ReferenceType.ALIAS_KEY_NAME, tokens);
    }

    public static HapiTokenAssociate tokenAssociate(String account, List<String> tokens) {
        return new HapiTokenAssociate(account, tokens);
    }

    public static HapiTokenCreate tokenCreate(String token) {
        return new HapiTokenCreate(token).name(token);
    }

    public static HapiSpecOperation wellKnownTokenEntities() {
        return blockingOrder(newKeyNamed(WELL_KNOWN_NFT_SUPPLY_KEY), cryptoCreate(TOKEN_TREASURY));
    }

    public static HapiSpecOperation createWellKnownNonFungibleToken(
            final String token, final int initialMint, final Consumer<HapiTokenCreate> tokenCustomizer) {
        if (initialMint > 10) {
            throw new IllegalArgumentException("Cannot mint more than 10 NFTs at a time");
        }
        return blockingOrder(
                sourcing(() -> {
                    final var creation = new HapiTokenCreate(token)
                            .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0L)
                            .treasury(TOKEN_TREASURY)
                            .supplyKey(WELL_KNOWN_NFT_SUPPLY_KEY)
                            .name(token);
                    tokenCustomizer.accept(creation);
                    return creation;
                }),
                mintToken(
                        token,
                        IntStream.range(0, initialMint)
                                .mapToObj(i -> ByteString.copyFromUtf8(randomUppercase(i + 1)))
                                .toList()));
    }

    public static HapiSpecOperation createWellKnownFungibleToken(
            final String token, final Consumer<HapiTokenCreate> tokenCustomizer) {
        return blockingOrder(sourcing(() -> {
            final var creation = new HapiTokenCreate(token)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .initialSupply(WELL_KNOWN_INITIAL_SUPPLY)
                    .treasury(TOKEN_TREASURY)
                    .name(token);
            tokenCustomizer.accept(creation);
            return creation;
        }));
    }

    public static HapiTokenUpdate tokenUpdate(String token) {
        return new HapiTokenUpdate(token);
    }

    public static HapiTokenUpdateNfts tokenUpdateNfts(String token, String metadata, List<Long> serialNumbers) {
        return new HapiTokenUpdateNfts(token, metadata, serialNumbers);
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    public static HapiTokenReject tokenReject(String account, Function<HapiSpec, TokenReference>... referencesSources) {
        return new HapiTokenReject(account, referencesSources);
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    public static HapiTokenReject tokenReject(Function<HapiSpec, TokenReference>... referencesSources) {
        return new HapiTokenReject(referencesSources);
    }

    public static HapiTokenFeeScheduleUpdate tokenFeeScheduleUpdate(String token) {
        return new HapiTokenFeeScheduleUpdate(token);
    }

    public static HapiTokenPause tokenPause(String token) {
        return new HapiTokenPause(token);
    }

    public static HapiTokenUnpause tokenUnpause(String token) {
        return new HapiTokenUnpause(token);
    }

    public static HapiTokenDelete tokenDelete(String token) {
        return new HapiTokenDelete(token);
    }

    public static HapiTokenFreeze tokenFreeze(String token, String account) {
        return new HapiTokenFreeze(token, account);
    }

    public static HapiTokenFreeze tokenFreezeWithAlias(String token, String alias) {
        return new HapiTokenFreeze(token, alias, ReferenceType.ALIAS_KEY_NAME);
    }

    public static HapiTokenUnfreeze tokenUnfreeze(String token, String account) {
        return new HapiTokenUnfreeze(token, account);
    }

    public static HapiTokenUnfreeze tokenUnfreezeWithAlias(String token, String alias) {
        return new HapiTokenUnfreeze(token, alias, ReferenceType.ALIAS_KEY_NAME);
    }

    public static HapiTokenKycGrant grantTokenKyc(String token, String account) {
        return new HapiTokenKycGrant(token, account);
    }

    public static HapiTokenKycGrant grantTokenKycWithAlias(String token, String alias) {
        return new HapiTokenKycGrant(token, alias, ReferenceType.ALIAS_KEY_NAME);
    }

    public static HapiTokenKycRevoke revokeTokenKyc(String token, String account) {
        return new HapiTokenKycRevoke(token, account);
    }

    public static HapiTokenKycRevoke revokeTokenKycWithAlias(String token, String alias) {
        return new HapiTokenKycRevoke(token, alias, ReferenceType.ALIAS_KEY_NAME);
    }

    public static HapiTokenWipe wipeTokenAccount(String token, String account, long amount) {
        return new HapiTokenWipe(token, account, amount);
    }

    public static HapiTokenWipe wipeTokenAccountWithAlias(String token, String alias, long amount) {
        return new HapiTokenWipe(token, alias, amount, ReferenceType.ALIAS_KEY_NAME);
    }

    public static HapiTokenWipe wipeTokenAccount(String token, String account, List<Long> serialNumbers) {
        return new HapiTokenWipe(token, account, serialNumbers);
    }

    public static HapiTokenMint mintToken(String token, long amount) {
        return new HapiTokenMint(token, amount);
    }

    public static HapiTokenMint mintToken(String token, List<ByteString> meta, String txName) {
        return new HapiTokenMint(token, meta, txName);
    }

    public static HapiTokenMint mintToken(String token, List<ByteString> metadata) {
        return new HapiTokenMint(token, metadata);
    }

    public static HapiTokenMint invalidMintToken(String token, List<ByteString> metadata, long amount) {
        return new HapiTokenMint(token, metadata, amount);
    }

    public static HapiTokenBurn burnToken(String token, long amount) {
        return new HapiTokenBurn(token, amount);
    }

    public static HapiTokenBurn burnToken(String token, List<Long> serialNumbers) {
        return new HapiTokenBurn(token, serialNumbers);
    }

    public static HapiTokenBurn invalidBurnToken(String token, List<Long> serialNumbers, long amount) {
        return new HapiTokenBurn(token, serialNumbers, amount);
    }

    /* SCHEDULE */
    public static <T extends HapiTxnOp<T>> HapiScheduleCreate<T> scheduleCreate(String scheduled, HapiTxnOp<T> txn) {
        return new HapiScheduleCreate<>(scheduled, txn);
    }

    public static HapiScheduleSign scheduleSign(String schedule) {
        return new HapiScheduleSign(schedule);
    }

    public static HapiScheduleCreate<HapiCryptoCreate> scheduleCreateFunctionless(String scheduled) {
        return new HapiScheduleCreate<>(scheduled, cryptoCreate("doomed")).functionless();
    }

    public static HapiScheduleDelete scheduleDelete(String schedule) {
        return new HapiScheduleDelete(schedule);
    }

    /* SYSTEM */
    public static HapiSysDelete systemFileDelete(String target) {
        return new HapiSysDelete().file(target);
    }

    public static HapiSysUndelete systemFileUndelete(String target) {
        return new HapiSysUndelete().file(target);
    }

    public static HapiSysDelete systemContractDelete(String target) {
        return new HapiSysDelete().contract(target);
    }

    public static HapiSysUndelete systemContractUndelete(String target) {
        return new HapiSysUndelete().contract(target);
    }

    /* NETWORK */
    public static <T extends HapiTxnOp<T>> HapiUncheckedSubmit<T> uncheckedSubmit(HapiTxnOp<T> subOp) {
        return new HapiUncheckedSubmit<>(subOp);
    }

    /* SMART CONTRACT */
    public static HapiContractCall contractCallFrom(String details) {
        return HapiContractCall.fromDetails(details);
    }

    public static HapiContractCall contractCall(String contract) {
        return new HapiContractCall(contract);
    }

    /**
     * This method allows the developer to invoke a contract function by the name of the called
     * contract and the name of the desired function
     *
     * @param contract the name of the contract
     * @param functionName the name of the function
     * @param params the arguments (if any) passed to the contract's function
     */
    public static HapiContractCall contractCall(String contract, String functionName, Object... params) {
        return innerContractCall(Optional.empty(), contract, functionName, params);
    }

    /**
     * This method allows the developer to invoke a contract function by the name of the called
     * contract and the name of the desired function
     *
     * @param variant the variant root directory for the contract
     * @param contract the name of the contract
     * @param functionName the name of the function
     * @param params the arguments (if any) passed to the contract's function
     */
    public static HapiContractCall contractCall(
            Optional<String> variant, String contract, String functionName, Object... params) {
        return innerContractCall(variant, contract, functionName, params);
    }

    /**
     * This method allows the developer to invoke a contract function by the name of the called
     * contract and the name of the desired function
     *
     * This overload allows for a variant root directory for the contract
     *
     * @param variant the optional variant root directory for the contract
     * @param contract the name of the contract
     * @param functionName the name of the function
     * @param params the arguments (if any) passed to the contract's function
     */
    private static HapiContractCall innerContractCall(
            final Optional<String> variant, final String contract, final String functionName, final Object... params) {
        final var abi = getABIFor(variant.orElse(VARIANT_NONE), FUNCTION, functionName, contract);
        return new HapiContractCall(abi, contract, params);
    }

    /**
     * This method allows the developer to invoke a contract function by the name of the called
     * contract and the name of the desired function and make an ethereum call
     *
     * @param contract the name of the contract
     * @param functionName the name of the function
     * @param params the arguments (if any) passed to the contract's function
     */
    public static HapiEthereumCall ethereumCall(String contract, String functionName, Object... params) {
        final var abi = getABIFor(FUNCTION, functionName, contract);
        return new HapiEthereumCall(abi, contract, params);
    }

    public static HapiEthereumCall ethereumCallWithFunctionAbi(
            boolean isTokenFlow, String contract, String abi, Object... params) {
        return new HapiEthereumCall(isTokenFlow, abi, contract, params);
    }

    /**
     * This method allows the developer to transfer hbars to an <b>account that's not a smart
     * contract</b> through an Ethereum transaction.
     *
     * @param account the name of the account in the registry
     * @param amount the amount of tinybars to be transferred from the Ethereum transaction sender
     *     to the specified account
     */
    public static HapiEthereumCall ethereumCryptoTransfer(String account, long amount) {
        return new HapiEthereumCall(account, amount);
    }

    public static HapiEthereumCall ethereumCryptoTransferToAlias(ByteString alias, long amount) {
        return new HapiEthereumCall(alias, amount);
    }

    public static HapiEthereumCall ethereumCryptoTransferToExplicit(@NonNull final byte[] to, final long amount) {
        return HapiEthereumCall.explicitlyTo(to, amount);
    }

    public static HapiEthereumCall ethereumCryptoTransferToAddress(@NonNull final Address address, final long amount) {
        return HapiEthereumCall.explicitlyTo(explicitBytesOf(address), amount);
    }

    /**
     * This method provides for the proper execution of specs, which execute contract calls with a
     * function ABI instead of function name
     *
     * @param contract the name of the contract
     * @param abi the contract's function ABI
     * @param params the arguments (if any) passed to the contract's function
     */
    public static HapiContractCall contractCallWithFunctionAbi(String contract, String abi, Object... params) {
        return new HapiContractCall(abi, contract, params);
    }

    public static HapiContractCall contractCall(String contract, String functionName, Function<HapiSpec, Object[]> fn) {
        final var abi = getABIFor(FUNCTION, functionName, contract);
        return new HapiContractCall(abi, contract, fn);
    }

    public static HapiContractCall contractCall(
            String contract, String functionName, Supplier<Object> parmeterSupplier) {
        final var abi = getABIFor(FUNCTION, functionName, contract);
        return new HapiContractCall(
                abi, contract, spec -> List.of(parmeterSupplier.get()).toArray());
    }

    public static HapiContractCall contractCallWithTuple(String contract, String abi, Function<HapiSpec, Tuple> fn) {
        return new HapiContractCall(abi, fn, contract);
    }

    public static HapiContractCall explicitContractCall(String contract, String abi, Object... params) {
        return new HapiContractCall(abi, contract, params);
    }

    public static HapiEthereumContractCreate ethereumContractCreate(final String contractName) {
        return new HapiEthereumContractCreate(contractName).bytecode(contractName);
    }

    public static HapiContractCreate contractCreate(final String contractName, final Object... constructorParams) {
        if (constructorParams.length > 0) {
            final var constructorABI = getABIFor(CONSTRUCTOR, EMPTY, contractName);
            return new HapiContractCreate(contractName, constructorABI, constructorParams).bytecode(contractName);
        } else {
            return new HapiContractCreate(contractName).bytecode(contractName);
        }
    }

    /**
     * Constructs a {@link HapiContractCreate} by letting the client code explicitly customize the {@link ContractCreateTransactionBody}.
     *
     * @param contractName the name the contract should register in this {@link HapiSpec}
     * @param spec the spec to use to customize the {@link ContractCreateTransactionBody}
     * @return a {@link HapiContractCreate} that can be used to create a contract
     */
    public static HapiContractCreate explicitContractCreate(
            final String contractName, final BiConsumer<HapiSpec, ContractCreateTransactionBody.Builder> spec) {
        return new HapiContractCreate(contractName, spec);
    }

    /**
     * Constructs a {@link HapiEthereumContractCreate} by letting the client code explicitly customize the {@link EthereumTransactionBody}.
     *
     * @param contractName the name the contract should register in this {@link HapiSpec}
     * @param spec the spec to use to customize the {@link EthereumTransactionBody}
     * @return a {@link HapiEthereumContractCreate} that can be used to create a contract
     */
    public static HapiEthereumContractCreate explicitEthereumTransaction(
            final String contractName, final BiConsumer<HapiSpec, EthereumTransactionBody.Builder> spec) {
        return new HapiEthereumContractCreate(contractName, spec);
    }

    public static HapiContractCreate createDefaultContract(final String name) {
        return new HapiContractCreate(name);
    }

    /**
     * Previously - when creating contracts we were passing a name, chosen by the developer, which
     * can differentiate from the name of the contract. The new implementation of the contract
     * creation depends on the exact name of the contract, which means, that we can not create
     * multiple instances of the contract with the same name. Therefore - in order to provide each
     * contract with a unique name - the developer must attach a suffix to happily create multiple
     * instances of the same contract, but with different names.
     *
     * <p><b>Example</b>:
     *
     * <p>final var contract = "TransferringContract";
     *
     * <p>contractCreate(contract).balance(10_000L).payingWith(ACCOUNT),
     *
     * <p>contractCustomCreate(contract, to).balance(10_000L).payingWith(ACCOUNT)
     *
     * @param contractName the name of the contract
     * @param suffix an additional String literal, which provides the instance of the contract with
     *     a unique identifier
     * @param constructorParams the arguments (if any) passed to the contract's constructor
     */
    public static HapiContractCreate contractCustomCreate(
            final String contractName, final String suffix, final Object... constructorParams) {
        if (constructorParams.length > 0) {
            final var constructorABI = getABIFor(CONSTRUCTOR, EMPTY, contractName);
            return new HapiContractCreate(contractName + suffix, constructorABI, constructorParams)
                    .bytecode(contractName);
        } else {
            return new HapiContractCreate(contractName + suffix).bytecode(contractName);
        }
    }

    public static HapiContractDelete contractDelete(String contract) {
        return new HapiContractDelete(contract);
    }

    public static HapiContractUpdate contractUpdate(String contract) {
        return new HapiContractUpdate(contract);
    }

    /**
     * This method enables the developer to upload one or many contract(s) bytecode(s)
     *
     * @param contractsNames the name(s) of the contract(s), which are to be deployed
     */
    public static HapiSpecOperation uploadInitCode(final String... contractsNames) {
        return uploadInitCode(Optional.empty(), VARIANT_NONE, contractsNames);
    }

    /**
     * This method enables the developer to upload one or many contract(s) bytecode(s) specifying a payer and
     * potentially a variant location for the contract(s)
     *
     * @param payer the payer of the file transaction
     * @param variant the variant location of the contract(s)
     * @param contractsNames the name(s) of the contract(s), which are to be deployed
     */
    public static HapiSpecOperation uploadInitCode(
            final Optional<String> payer, final String variant, final String... contractsNames) {
        return withOpContext((spec, ctxLog) -> {
            final List<SpecOperation> ops = new ArrayList<>();
            for (String contractName : contractsNames) {
                final var path = getResourcePath(defaultContractsRoot(variant), contractName, ".bin");
                final var file = new HapiFileCreate(contractName);
                final var updatedFile = updateLargeFile(payer.orElse(GENESIS), contractName, extractByteCode(path));
                ops.add(file);
                ops.add(updatedFile);
            }
            allRunFor(spec, ops);
        });
    }

    /**
     *  This method enables uploading a contract bytecode with the constructor parameters (if present) appended at the end of the file
     *  Used for ethereum create conversion when we need to pass constructor arguments
     * @param contractName the name of the contract, which is to be deployed
     * @param abi the abi of the contract
     * @param args the constructor arguments
     * @return HapiSpecOperation
     */
    public static SpecOperation updateInitCodeWithConstructorArgs(
            final Optional<String> payer, final String contractName, final String abi, final Object... args) {
        return withOpContext((spec, ctxLog) -> {
            List<SpecOperation> ops = new ArrayList<>();

            final var path = getResourcePath(contractName, ".bin");

            // concatenate bytecode with params
            final var bytecode = extractByteCode(path).concat(TxnUtils.constructorArgsToByteString(abi, args));
            final var updatedFile = updateLargeFile(payer.orElse(GENESIS), contractName, bytecode);
            ops.add(updatedFile);

            allRunFor(spec, ops);
        });
    }

    public static HapiSpecOperation uploadSingleInitCode(
            final String contractName, final long expiry, final String payingWith, final LongConsumer exposingTo) {
        return withOpContext((spec, ctxLog) -> {
            final List<SpecOperation> ops = new ArrayList<>();
            final var path = getResourcePath(contractName, ".bin");
            final var file = new HapiFileCreate(contractName)
                    .payingWith(payingWith)
                    .exposingNumTo(exposingTo)
                    .expiry(expiry);
            final var updatedFile = updateLargeFile(GENESIS, contractName, extractByteCode(path));
            ops.add(file);
            ops.add(updatedFile);
            allRunFor(spec, ops);
        });
    }

    public static HapiSpecOperation uploadSingleInitCode(
            final String contractName, final ResponseCodeEnum... statuses) {
        return withOpContext((spec, ctxLog) -> {
            final List<SpecOperation> ops = new ArrayList<>();
            final var path = getResourcePath(contractName, ".bin");
            final var file = new HapiFileCreate(contractName).hasRetryPrecheckFrom(statuses);
            final var updatedFile = updateLargeFile(GENESIS, contractName, extractByteCode(path));
            ops.add(file);
            ops.add(updatedFile);
            allRunFor(spec, ops);
        });
    }

    /**
     * This method enables uploading a contract bytecode with the constructor parameters (if
     * present) appended at the end of the file
     *
     * @param contractName the name of the contract, which is to be deployed
     * @param abi the abi of the contract
     * @param args the constructor arguments
     */
    public static HapiSpecOperation uploadInitCodeWithConstructorArguments(
            final String contractName, final String abi, final Object... args) {
        return withOpContext((spec, ctxLog) -> {
            final List<SpecOperation> ops = new ArrayList<>();

            final var path = getResourcePath(contractName, ".bin");
            final var file = new HapiFileCreate(contractName);
            final var fileByteCode = extractByteCode(path);
            final byte[] params = args.length == 0
                    ? new byte[] {}
                    : com.esaulpaugh.headlong.abi.Function.fromJson(abi)
                            .encodeCall(Tuple.from(args))
                            .array();
            final var updatedFile = updateLargeFile(
                    GENESIS,
                    contractName,
                    params.length == 0 ? fileByteCode : fileByteCode.concat(ByteString.copyFrom(params)));
            ops.add(file);
            ops.add(updatedFile);
            allRunFor(spec, ops);
        });
    }

    /* SYSTEM */
    public static HapiFreeze hapiFreeze(final Instant freezeStartTime) {
        return new HapiFreeze().startingAt(freezeStartTime);
    }

    public static HapiStateSignature hapiStateSignature() {
        return new HapiStateSignature();
    }

    /* UTIL */
    public static HapiUtilPrng hapiPrng() {
        return new HapiUtilPrng();
    }

    public static HapiUtilPrng hapiPrng(int range) {
        return new HapiUtilPrng(range);
    }

    public static HapiAtomicBatch atomicBatch(HapiTxnOp<?>... ops) {
        return new HapiAtomicBatch(ops);
    }

    public static HapiLambdaSStore accountLambdaSStore(@NonNull final String account, final long hookId) {
        return new HapiLambdaSStore(HookEntityId.EntityIdOneOfType.ACCOUNT_ID, account, hookId);
    }
}
