// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.balanceof.BalanceOfTranslator.BALANCE_OF;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_BESU_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asHeadlongAddress;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.token.TokenMintTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.associations.AssociationsDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.associations.AssociationsTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.balanceof.BalanceOfCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.balanceof.BalanceOfTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.decimals.DecimalsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.decimals.DecimalsTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.isapprovedforall.IsApprovedForAllCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.isapprovedforall.IsApprovedForAllTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.mint.MintDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.mint.MintTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.name.NameCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.name.NameTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ownerof.OwnerOfCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ownerof.OwnerOfTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.symbol.SymbolCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.symbol.SymbolTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.tokenuri.TokenUriCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.tokenuri.TokenUriTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.totalsupply.TotalSupplyCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.totalsupply.TotalSupplyTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ClassicTransfersCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ClassicTransfersDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ClassicTransfersTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.Erc20TransfersCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.Erc20TransfersTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.Erc721TransferFromCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.Erc721TransferFromTranslator;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;

class HtsCallAttemptTest extends CallAttemptTestBase {

    @Mock
    private VerificationStrategy strategy;

    @Mock
    private AssociationsDecoder associationsDecoder;

    @Mock
    private ClassicTransfersDecoder classicTransfersDecoder;

    @Mock
    private MintDecoder mintDecoder;

    @Mock
    private ContractMetrics contractMetrics;

    private List<CallTranslator<HtsCallAttempt>> callTranslators;

    @BeforeEach
    void setUp() {
        callTranslators = List.of(
                new AssociationsTranslator(associationsDecoder, systemContractMethodRegistry, contractMetrics),
                new Erc20TransfersTranslator(systemContractMethodRegistry, contractMetrics),
                new Erc721TransferFromTranslator(systemContractMethodRegistry, contractMetrics),
                new MintTranslator(mintDecoder, systemContractMethodRegistry, contractMetrics),
                new ClassicTransfersTranslator(classicTransfersDecoder, systemContractMethodRegistry, contractMetrics),
                new BalanceOfTranslator(systemContractMethodRegistry, contractMetrics),
                new IsApprovedForAllTranslator(systemContractMethodRegistry, contractMetrics),
                new NameTranslator(systemContractMethodRegistry, contractMetrics),
                new TotalSupplyTranslator(systemContractMethodRegistry, contractMetrics),
                new SymbolTranslator(systemContractMethodRegistry, contractMetrics),
                new TokenUriTranslator(systemContractMethodRegistry, contractMetrics),
                new OwnerOfTranslator(systemContractMethodRegistry, contractMetrics),
                new DecimalsTranslator(systemContractMethodRegistry, contractMetrics));
    }

    @Test
    void nonLongZeroAddressesArentTokens() {
        final var input =
                TestHelpers.bytesForRedirect(Erc20TransfersTranslator.ERC_20_TRANSFER.selector(), OWNER_BESU_ADDRESS);
        final var subject = createHtsCallAttempt(input, false, callTranslators);
        assertNull(subject.redirectToken());
    }

    @Test
    void invalidSelectorLeadsToMissingCall() {
        given(nativeOperations.getToken(any())).willReturn(FUNGIBLE_TOKEN);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var input = TestHelpers.bytesForRedirect(new byte[4], NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var subject = createHtsCallAttempt(input, false, callTranslators);
        assertNull(subject.asExecutableCall());
    }

    @Test
    void constructsDecimals() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var input = TestHelpers.bytesForRedirect(
                DecimalsTranslator.DECIMALS.encodeCallWithArgs().array(), NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var subject = createHtsCallAttempt(input, false, callTranslators);
        assertInstanceOf(DecimalsCall.class, subject.asExecutableCall());
    }

    @Test
    void constructsTokenUri() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var input = TestHelpers.bytesForRedirect(
                TokenUriTranslator.TOKEN_URI.encodeCallWithArgs(BigInteger.ONE).array(), NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var subject = createHtsCallAttempt(input, false, callTranslators);
        assertInstanceOf(TokenUriCall.class, subject.asExecutableCall());
    }

    @Test
    void constructsOwnerOf() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var input = TestHelpers.bytesForRedirect(
                OwnerOfTranslator.OWNER_OF.encodeCallWithArgs(BigInteger.ONE).array(), NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var subject = createHtsCallAttempt(input, false, callTranslators);
        assertInstanceOf(OwnerOfCall.class, subject.asExecutableCall());
    }

    @Test
    void constructsBalanceOf() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var input = TestHelpers.bytesForRedirect(
                BALANCE_OF
                        .encodeCallWithArgs(asHeadlongAddress(OWNER_BESU_ADDRESS))
                        .array(),
                NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var subject = createHtsCallAttempt(input, false, callTranslators);
        assertInstanceOf(BalanceOfCall.class, subject.asExecutableCall());
    }

    @Test
    void constructsIsApprovedForAllErc() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var address = asHeadlongAddress(OWNER_BESU_ADDRESS);
        final var input = TestHelpers.bytesForRedirect(
                IsApprovedForAllTranslator.ERC_IS_APPROVED_FOR_ALL
                        .encodeCallWithArgs(address, address)
                        .array(),
                NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var subject = createHtsCallAttempt(input, false, callTranslators);
        assertInstanceOf(IsApprovedForAllCall.class, subject.asExecutableCall());
    }

    @Test
    void constructsIsApprovedForAllClassic() {
        final var address = asHeadlongAddress(OWNER_BESU_ADDRESS);
        final var input = Bytes.wrap(IsApprovedForAllTranslator.CLASSIC_IS_APPROVED_FOR_ALL
                .encodeCallWithArgs(address, address, address)
                .array());
        final var subject = createHtsCallAttempt(input, false, callTranslators);
        assertInstanceOf(IsApprovedForAllCall.class, subject.asExecutableCall());
    }

    @Test
    void constructsTotalSupply() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var input = TestHelpers.bytesForRedirect(
                TotalSupplyTranslator.TOTAL_SUPPLY.encodeCallWithArgs().array(), NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var subject = createHtsCallAttempt(input, false, callTranslators);
        assertInstanceOf(TotalSupplyCall.class, subject.asExecutableCall());
    }

    @Test
    void constructsName() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var input = TestHelpers.bytesForRedirect(
                NameTranslator.NAME.encodeCallWithArgs().array(), NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var subject = createHtsCallAttempt(input, false, callTranslators);
        assertInstanceOf(NameCall.class, subject.asExecutableCall());
    }

    @Test
    void constructsSymbol() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var input = TestHelpers.bytesForRedirect(
                SymbolTranslator.SYMBOL.encodeCallWithArgs().array(), NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var subject = createHtsCallAttempt(input, false, callTranslators);
        assertInstanceOf(SymbolCall.class, subject.asExecutableCall());
    }

    @Test
    void constructsErc721TransferFromRedirectToNonfungible() {
        given(nativeOperations.getToken(any())).willReturn(NON_FUNGIBLE_TOKEN);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(addressIdConverter.convertSender(OWNER_BESU_ADDRESS)).willReturn(A_NEW_ACCOUNT_ID);
        final var input = TestHelpers.bytesForRedirect(
                Erc721TransferFromTranslator.ERC_721_TRANSFER_FROM
                        .encodeCallWithArgs(
                                asHeadlongAddress(OWNER_BESU_ADDRESS),
                                asHeadlongAddress(NON_SYSTEM_LONG_ZERO_ADDRESS),
                                BigInteger.ONE)
                        .array(),
                NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(verificationStrategies.activatingOnlyContractKeysFor(OWNER_BESU_ADDRESS, true, nativeOperations))
                .willReturn(strategy);
        final var subject = createHtsCallAttempt(input, true, callTranslators);
        assertInstanceOf(Erc721TransferFromCall.class, subject.asExecutableCall());
    }

    @Test
    void constructsErc20TransferFromRedirectToFungible() {
        given(nativeOperations.getToken(any())).willReturn(FUNGIBLE_TOKEN);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(addressIdConverter.convertSender(OWNER_BESU_ADDRESS)).willReturn(A_NEW_ACCOUNT_ID);
        final var input = TestHelpers.bytesForRedirect(
                Erc20TransfersTranslator.ERC_20_TRANSFER_FROM
                        .encodeCallWithArgs(
                                asHeadlongAddress(OWNER_BESU_ADDRESS),
                                asHeadlongAddress(NON_SYSTEM_LONG_ZERO_ADDRESS),
                                BigInteger.TWO)
                        .array(),
                NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(verificationStrategies.activatingOnlyContractKeysFor(OWNER_BESU_ADDRESS, true, nativeOperations))
                .willReturn(strategy);
        final var subject = createHtsCallAttempt(input, true, callTranslators);
        assertInstanceOf(Erc20TransfersCall.class, subject.asExecutableCall());
    }

    @Test
    void constructsErc20TransferRedirectToFungible() {
        given(nativeOperations.getToken(any())).willReturn(FUNGIBLE_TOKEN);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(addressIdConverter.convertSender(OWNER_BESU_ADDRESS)).willReturn(A_NEW_ACCOUNT_ID);
        final var input = TestHelpers.bytesForRedirect(
                Erc20TransfersTranslator.ERC_20_TRANSFER
                        .encodeCallWithArgs(asHeadlongAddress(NON_SYSTEM_LONG_ZERO_ADDRESS), BigInteger.TWO)
                        .array(),
                NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(verificationStrategies.activatingOnlyContractKeysFor(OWNER_BESU_ADDRESS, true, nativeOperations))
                .willReturn(strategy);
        final var subject = createHtsCallAttempt(input, true, callTranslators);
        assertInstanceOf(Erc20TransfersCall.class, subject.asExecutableCall());
    }

    @ParameterizedTest
    @CsvSource({
        "false,false,0x49146bde",
        "false,false,0x2e63879b",
        "false,false,0x099794e8",
        "false,false,0x78b63918",
        "false,true,0x0a754de6",
        "false,true,0x5c9217e0",
        "true,true,0x0a754de6",
        "true,true,0x5c9217e0",
    })
    void constructsAssociations(boolean useExplicitCall, boolean isRedirect, String hexedSelector) {
        final var selector = CommonUtils.unhex(hexedSelector.substring(2));
        final var selectorHex = hexedSelector.substring(2);
        // Even the approval-based transfers need a verification strategy since the receiver could have
        // receiverSigRequired on; in which case the sender will need to activate a contract id key
        given(verificationStrategies.activatingOnlyContractKeysFor(OWNER_BESU_ADDRESS, true, nativeOperations))
                .willReturn(strategy);
        if (AssociationsTranslator.ASSOCIATE_ONE.selectorHex().equals(selectorHex)) {
            given(associationsDecoder.decodeAssociateOne(any())).willReturn(TransactionBody.DEFAULT);
        } else if (AssociationsTranslator.ASSOCIATE_MANY.selectorHex().equals(selectorHex)) {
            given(associationsDecoder.decodeAssociateMany(any())).willReturn(TransactionBody.DEFAULT);
        } else if (AssociationsTranslator.DISSOCIATE_ONE.selectorHex().equals(selectorHex)) {
            given(associationsDecoder.decodeDissociateOne(any())).willReturn(TransactionBody.DEFAULT);
        } else if (AssociationsTranslator.DISSOCIATE_MANY.selectorHex().equals(selectorHex)) {
            given(associationsDecoder.decodeDissociateMany(any())).willReturn(TransactionBody.DEFAULT);
        } else if (AssociationsTranslator.HRC_ASSOCIATE.selectorHex().equals(selectorHex)) {
            given(associationsDecoder.decodeHrcAssociate(any())).willReturn(TransactionBody.DEFAULT);
        } else if (AssociationsTranslator.HRC_DISSOCIATE.selectorHex().equals(selectorHex)) {
            given(associationsDecoder.decodeHrcDissociate(any())).willReturn(TransactionBody.DEFAULT);
        }
        final var input = encodeInput(useExplicitCall, isRedirect, selector);
        if (isRedirect) {
            given(nativeOperations.getToken(any())).willReturn(FUNGIBLE_TOKEN);
            given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        }

        given(addressIdConverter.convertSender(OWNER_BESU_ADDRESS)).willReturn(A_NEW_ACCOUNT_ID);

        final var subject = createHtsCallAttempt(input, true, callTranslators);

        assertInstanceOf(DispatchForResponseCodeHtsCall.class, subject.asExecutableCall());
        assertArrayEquals(selector, subject.selector());
        assertEquals(isRedirect, subject.isRedirect());
        if (isRedirect) {
            assertEquals(FUNGIBLE_TOKEN, subject.redirectToken());
            assertArrayEquals(selector, subject.input().slice(0, 4).toArrayUnsafe());
        } else {
            assertThrows(IllegalStateException.class, subject::redirectToken);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "0x189a554c",
        "0x0e71804f",
        "0xeca36917",
        "0x82bba493",
        "0x5cfc9011",
        "0x2c4ba191",
        "0x15dacbea",
        "0x9b23d3d9",
    })
    void constructsClassicTransfers(String hexedSelector) {
        final var selector = CommonUtils.unhex(hexedSelector.substring(2));
        final var selectorHex = hexedSelector.substring(2);
        // Even the approval-based transfers need a verification strategy since the receiver could have
        // receiverSigRequired on; in which case the sender will need to activate a contract id key
        given(verificationStrategies.activatingOnlyContractKeysFor(OWNER_BESU_ADDRESS, true, nativeOperations))
                .willReturn(strategy);
        if (ClassicTransfersTranslator.CRYPTO_TRANSFER.selectorHex().equals(selectorHex)) {
            given(classicTransfersDecoder.decodeCryptoTransfer(any(), any())).willReturn(TransactionBody.DEFAULT);
        } else if (ClassicTransfersTranslator.CRYPTO_TRANSFER_V2.selectorHex().equals(selectorHex)) {
            given(classicTransfersDecoder.decodeCryptoTransferV2(any(), any())).willReturn(TransactionBody.DEFAULT);
        } else if (ClassicTransfersTranslator.TRANSFER_TOKENS.selectorHex().equals(selectorHex)) {
            given(classicTransfersDecoder.decodeTransferTokens(any(), any())).willReturn(TransactionBody.DEFAULT);
        } else if (ClassicTransfersTranslator.TRANSFER_TOKEN.selectorHex().equals(selectorHex)) {
            given(classicTransfersDecoder.decodeTransferToken(any(), any())).willReturn(TransactionBody.DEFAULT);
        } else if (ClassicTransfersTranslator.TRANSFER_NFTS.selectorHex().equals(selectorHex)) {
            given(classicTransfersDecoder.decodeTransferNfts(any(), any())).willReturn(TransactionBody.DEFAULT);
        } else if (ClassicTransfersTranslator.TRANSFER_NFT.selectorHex().equals(selectorHex)) {
            given(classicTransfersDecoder.decodeTransferNft(any(), any())).willReturn(TransactionBody.DEFAULT);
        } else if (ClassicTransfersTranslator.TRANSFER_FROM.selectorHex().equals(selectorHex)) {
            given(classicTransfersDecoder.decodeHrcTransferFrom(any(), any())).willReturn(TransactionBody.DEFAULT);
        } else if (ClassicTransfersTranslator.TRANSFER_NFT_FROM.selectorHex().equals(selectorHex)) {
            given(classicTransfersDecoder.decodeHrcTransferNftFrom(any(), any()))
                    .willReturn(TransactionBody.DEFAULT);
        }
        final var input = Bytes.wrap(selector);
        given(addressIdConverter.convertSender(OWNER_BESU_ADDRESS)).willReturn(A_NEW_ACCOUNT_ID);

        final var subject = createHtsCallAttempt(input, true, callTranslators);

        assertInstanceOf(ClassicTransfersCall.class, subject.asExecutableCall());
        assertArrayEquals(selector, subject.selector());
        assertFalse(subject.isRedirect());
        assertThrows(IllegalStateException.class, subject::redirectToken);
    }

    enum LinkedTokenType {
        NON_FUNGIBLE,
        FUNGIBLE
    }

    @ParameterizedTest
    @CsvSource({
        "0x278e0b88,FUNGIBLE",
        "0x278e0b88,NON_FUNGIBLE",
        "0xe0f4059a,FUNGIBLE",
        "0xe0f4059a,NON_FUNGIBLE",
    })
    void constructsMints(String hexedSelector, LinkedTokenType linkedTokenType) {
        given(verificationStrategies.activatingOnlyContractKeysFor(OWNER_BESU_ADDRESS, false, nativeOperations))
                .willReturn(strategy);
        given(addressIdConverter.convertSender(OWNER_BESU_ADDRESS)).willReturn(A_NEW_ACCOUNT_ID);
        lenient()
                .when(mintDecoder.decodeMint(any()))
                .thenReturn(TransactionBody.newBuilder()
                        .tokenMint(TokenMintTransactionBody.DEFAULT)
                        .build());
        lenient()
                .when(mintDecoder.decodeMintV2(any()))
                .thenReturn(TransactionBody.newBuilder()
                        .tokenMint(TokenMintTransactionBody.DEFAULT)
                        .build());
        final var selector = CommonUtils.unhex(hexedSelector.substring(2));
        final var useV2 = Arrays.equals(MintTranslator.MINT_V2.selector(), selector);
        final Bytes input;
        if (linkedTokenType == LinkedTokenType.FUNGIBLE) {
            input = useV2
                    ? Bytes.wrap(MintTranslator.MINT_V2
                            .encodeCallWithArgs(asHeadlongAddress(NON_SYSTEM_LONG_ZERO_ADDRESS), 1L, new byte[0][])
                            .array())
                    : Bytes.wrap(MintTranslator.MINT
                            .encodeCallWithArgs(
                                    asHeadlongAddress(NON_SYSTEM_LONG_ZERO_ADDRESS), BigInteger.ONE, new byte[0][])
                            .array());
        } else {
            input = useV2
                    ? Bytes.wrap(MintTranslator.MINT_V2
                            .encodeCallWithArgs(
                                    asHeadlongAddress(NON_SYSTEM_LONG_ZERO_ADDRESS), 0L, new byte[][] {new byte[0]})
                            .array())
                    : Bytes.wrap(MintTranslator.MINT
                            .encodeCallWithArgs(
                                    asHeadlongAddress(NON_SYSTEM_LONG_ZERO_ADDRESS),
                                    BigInteger.ZERO,
                                    new byte[][] {new byte[0]})
                            .array());
        }

        final var subject = createHtsCallAttempt(input, false, callTranslators);

        assertInstanceOf(DispatchForResponseCodeHtsCall.class, subject.asExecutableCall());
        assertArrayEquals(selector, subject.selector());
        assertFalse(subject.isRedirect());
    }

    private Bytes encodeInput(final boolean useExplicitCall, final boolean isRedirect, final byte[] selector) {
        if (isRedirect) {
            return useExplicitCall
                    ? Bytes.wrap(HtsCallAttempt.REDIRECT_FOR_TOKEN
                            .encodeCallWithArgs(
                                    asHeadlongAddress(NON_SYSTEM_LONG_ZERO_ADDRESS.toArrayUnsafe()),
                                    Bytes.wrap(selector).toArrayUnsafe())
                            .array())
                    : bytesForRedirect(selector);
        } else {
            return Bytes.wrap(selector);
        }
    }

    private Bytes bytesForRedirect(final byte[] subSelector) {
        return TestHelpers.bytesForRedirect(subSelector, NON_SYSTEM_LONG_ZERO_ADDRESS);
    }
}
