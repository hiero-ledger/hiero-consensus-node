// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.HEDERA_TOKEN_V1;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.NoNftTransfers.changingNoNftOwners;
import static com.hedera.services.bdd.spec.assertions.NonFungibleTransfers.changingNFTBalances;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class ContractCallNftRecordsTest {
    /** Init code of a contract that CALLs 0x167 twice, with calldata framed as {@code [uint256 len1][call1][call2]}. */
    private static final String FORWARDER_INIT = "6034" + "80" + "600b" + "6000" + "39" + "6000" + "f3"
            + "600035" + "80" + "6020" + "6000" + "37"
            + "6000" + "6000" + "82" + "6000" + "6000" + "610167" + "5a" + "f1" + "50"
            + "6020" + "01" + "80" + "36" + "03" + "80" + "91" + "6000" + "37"
            + "6000" + "6000" + "82" + "6000" + "6000" + "610167" + "5a" + "f1" + "50" + "50" + "00";

    private static final Function TRANSFER_NFT = new Function("transferNFT(address,address,address,int64)");
    private static final Function MINT_TOKEN = new Function("mintToken(address,int64,bytes[])");
    private static final Function UPDATE_TOKEN_INFO = new Function("updateTokenInfo(address," + HEDERA_TOKEN_V1 + ")");
    private static final Address ZERO_ADDRESS = asHeadlongAddress(new byte[20]);

    private static final String FORWARDER = "forwarder";
    private static final String FORWARDER_KEY = "forwarderKey";
    private static final String PAYER = "payer";
    private static final String HOLDER = "holder";
    private static final String OLD_TREASURY = "oldTreasury";
    private static final String NEW_TREASURY = "newTreasury";
    private static final String NFT = "nft";
    private static final String NFT_NAME = "nftName";
    private static final String NFT_SYMBOL = "NFTS";
    private static final String CALL = "call";
    private static final String BATCH = "batch";
    private static final long GAS = 2_000_000L;

    @HapiTest
    final Stream<DynamicTest> callWithNftTransferAndTokenUpdate() {
        return hapiTest(flattened(
                setup(),
                sourcingContextual(spec -> contractCall(FORWARDER)
                        .withExplicitRawParams(forwarderCalldata(spec, transferCall(spec)))
                        .gas(GAS)
                        .payingWith(PAYER)
                        .via(CALL)),
                childRecordsCheck(
                        CALL,
                        SUCCESS,
                        parent -> parent.tokenTransfers(changingNoNftOwners()),
                        recordWith().tokenTransfers(changingNFTBalances().including(NFT, HOLDER, OLD_TREASURY, 1L)),
                        recordWith()
                                .tokenTransfers(changingNFTBalances().including(NFT, OLD_TREASURY, NEW_TREASURY, -1L))),
                getAccountBalance(HOLDER).hasTokenBalance(NFT, 0),
                getAccountBalance(OLD_TREASURY).hasTokenBalance(NFT, 0),
                getAccountBalance(NEW_TREASURY).hasTokenBalance(NFT, 2),
                getTokenInfo(NFT).hasTreasury(NEW_TREASURY)));
    }

    @HapiTest
    final Stream<DynamicTest> callWithNftMintAndTokenUpdate() {
        return hapiTest(flattened(
                setup(),
                sourcingContextual(spec -> contractCall(FORWARDER)
                        .withExplicitRawParams(forwarderCalldata(spec, mintCall(spec)))
                        .gas(GAS)
                        .payingWith(PAYER)
                        .via(CALL)),
                getTxnRecord(CALL).hasPriority(recordWith().tokenTransfers(changingNoNftOwners())),
                getAccountBalance(HOLDER).hasTokenBalance(NFT, 1),
                getAccountBalance(OLD_TREASURY).hasTokenBalance(NFT, 0),
                getAccountBalance(NEW_TREASURY).hasTokenBalance(NFT, 2),
                getTokenNftInfo(NFT, 3).hasAccountID(NEW_TREASURY)));
    }

    @HapiTest
    final Stream<DynamicTest> batchedCallWithNftTransferAndTokenUpdate() {
        return hapiTest(flattened(
                setup(),
                sourcingContextual(spec -> atomicBatch(contractCall(FORWARDER)
                                .withExplicitRawParams(forwarderCalldata(spec, transferCall(spec)))
                                .gas(GAS)
                                .payingWith(PAYER)
                                .batchKey(PAYER)
                                .via(CALL))
                        .payingWith(PAYER)
                        .via(BATCH)),
                getTxnRecord(BATCH).hasPriority(recordWith().tokenTransfers(changingNoNftOwners())),
                getTxnRecord(CALL).hasPriority(recordWith().tokenTransfers(changingNoNftOwners())),
                getAccountBalance(HOLDER).hasTokenBalance(NFT, 0),
                getAccountBalance(NEW_TREASURY).hasTokenBalance(NFT, 2),
                getTokenInfo(NFT).hasTreasury(NEW_TREASURY)));
    }

    private static SpecOperation[] setup() {
        return new SpecOperation[] {
            contractCreate(FORWARDER)
                    .inlineInitCode(ByteString.fromHex(FORWARDER_INIT))
                    .gas(200_000L),
            newKeyNamed(FORWARDER_KEY).shape(threshOf(1, ED25519, CONTRACT).signedWith(sigs(ON, FORWARDER))),
            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
            cryptoCreate(HOLDER).key(FORWARDER_KEY),
            cryptoCreate(OLD_TREASURY).key(FORWARDER_KEY),
            cryptoCreate(NEW_TREASURY).key(FORWARDER_KEY),
            tokenCreate(NFT)
                    .name(NFT_NAME)
                    .symbol(NFT_SYMBOL)
                    .tokenType(NON_FUNGIBLE_UNIQUE)
                    .initialSupply(0)
                    .treasury(OLD_TREASURY)
                    .adminKey(FORWARDER_KEY)
                    .supplyKey(FORWARDER_KEY),
            mintToken(NFT, List.of(ByteString.copyFromUtf8("one"), ByteString.copyFromUtf8("two"))),
            tokenAssociate(HOLDER, NFT),
            tokenAssociate(NEW_TREASURY, NFT),
            cryptoTransfer(movingUnique(NFT, 1L).between(OLD_TREASURY, HOLDER))
        };
    }

    private static byte[] transferCall(final HapiSpec spec) {
        return TRANSFER_NFT
                .encodeCallWithArgs(
                        tokenAddress(spec), accountAddress(spec, HOLDER), accountAddress(spec, OLD_TREASURY), 1L)
                .array();
    }

    private static byte[] mintCall(final HapiSpec spec) {
        return MINT_TOKEN
                .encodeCallWithArgs(tokenAddress(spec), 0L, new byte[][] {"three".getBytes(StandardCharsets.UTF_8)})
                .array();
    }

    private static byte[] forwarderCalldata(final HapiSpec spec, final byte[] firstCall) {
        final var updateCall = UPDATE_TOKEN_INFO
                .encodeCallWithArgs(
                        tokenAddress(spec),
                        Tuple.from(
                                NFT_NAME,
                                NFT_SYMBOL,
                                accountAddress(spec, NEW_TREASURY),
                                "",
                                false,
                                0L,
                                false,
                                new Tuple[0],
                                Tuple.of(0L, ZERO_ADDRESS, 0L)))
                .array();
        final var calldata = ByteBuffer.allocate(32 + firstCall.length + updateCall.length);
        calldata.position(24).putLong(firstCall.length).put(firstCall).put(updateCall);
        return calldata.array();
    }

    private static Address tokenAddress(final HapiSpec spec) {
        return asHeadlongAddress(asAddress(spec.registry().getTokenID(NFT)));
    }

    private static Address accountAddress(final HapiSpec spec, final String account) {
        return asHeadlongAddress(asAddress(spec.registry().getAccountID(account)));
    }
}
