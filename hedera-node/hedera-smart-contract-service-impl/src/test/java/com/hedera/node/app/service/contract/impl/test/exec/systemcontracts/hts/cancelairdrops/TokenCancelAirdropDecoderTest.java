// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.cancelairdrops;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PENDING_AIRDROP_ID_LIST_TOO_LONG;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.cancelairdrops.TokenCancelAirdropTranslator.CANCEL_AIRDROPS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.cancelairdrops.TokenCancelAirdropTranslator.HRC_CANCEL_AIRDROP_FT;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.cancelairdrops.TokenCancelAirdropTranslator.HRC_CANCEL_AIRDROP_NFT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ACCOUNT_AS_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asHeadlongAddress;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.cancelairdrops.TokenCancelAirdropDecoder;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.TokensConfig;
import com.swirlds.config.api.Configuration;
import java.util.ArrayList;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class TokenCancelAirdropDecoderTest extends CallAttemptTestBase {

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private Configuration configuration;

    @Mock
    private TokensConfig tokensConfig;

    private TokenCancelAirdropDecoder subject;

    @BeforeEach
    void setup() {
        subject = new TokenCancelAirdropDecoder();

        lenient().when(attempt.addressIdConverter()).thenReturn(addressIdConverter);
        lenient().when(attempt.configuration()).thenReturn(configuration);
        lenient().when(attempt.enhancement()).thenReturn(mockEnhancement());
        lenient().when(configuration.getConfigData(TokensConfig.class)).thenReturn(tokensConfig);
    }

    @Test
    void cancelAirdropDecoder1FTTest() {
        // given:
        given(tokensConfig.maxAllowedPendingAirdropsToCancel()).willReturn(10);
        given(nativeOperations.getToken(FUNGIBLE_TOKEN_ID)).willReturn(FUNGIBLE_TOKEN);
        given(addressIdConverter.convert(asHeadlongAddress(SENDER_ID.accountNum())))
                .willReturn(SENDER_ID);
        given(addressIdConverter.convert(OWNER_ACCOUNT_AS_ADDRESS)).willReturn(OWNER_ID);

        final var encoded = Bytes.wrapByteBuffer(CANCEL_AIRDROPS.encodeCall(Tuple.singleton(new Tuple[] {
            Tuple.of(
                    asHeadlongAddress(SENDER_ID.accountNum()),
                    OWNER_ACCOUNT_AS_ADDRESS,
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    0L)
        })));
        given(attempt.inputBytes()).willReturn(encoded.toArrayUnsafe());

        var expected = new ArrayList<PendingAirdropId>();
        expected.add(PendingAirdropId.newBuilder()
                .senderId(SENDER_ID)
                .receiverId(OWNER_ID)
                .fungibleTokenType(FUNGIBLE_TOKEN_ID)
                .build());
        // when:
        final var decoded = subject.decodeCancelAirdrop(attempt);

        // then:
        assertNotNull(decoded.tokenCancelAirdrop());
        assertEquals(expected, decoded.tokenCancelAirdrop().pendingAirdrops());
    }

    @Test
    void failsIfPendingAirdropsAboveLimit() {
        // given:
        given(tokensConfig.maxAllowedPendingAirdropsToCancel()).willReturn(2);

        final var tuple = Tuple.of(
                asHeadlongAddress(SENDER_ID.accountNum()),
                OWNER_ACCOUNT_AS_ADDRESS,
                FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                0L);
        final var encoded =
                Bytes.wrapByteBuffer(CANCEL_AIRDROPS.encodeCall(Tuple.singleton(new Tuple[] {tuple, tuple, tuple})));
        given(attempt.inputBytes()).willReturn(encoded.toArrayUnsafe());

        assertThatExceptionOfType(HandleException.class)
                .isThrownBy(() -> subject.decodeCancelAirdrop(attempt))
                .withMessage(PENDING_AIRDROP_ID_LIST_TOO_LONG.protoName());
    }

    @Test
    void failsIfTokenIsNull() {
        // given:
        given(tokensConfig.maxAllowedPendingAirdropsToCancel()).willReturn(10);
        given(nativeOperations.getToken(FUNGIBLE_TOKEN_ID)).willReturn(null);
        given(addressIdConverter.convert(asHeadlongAddress(SENDER_ID.accountNum())))
                .willReturn(SENDER_ID);
        given(addressIdConverter.convert(OWNER_ACCOUNT_AS_ADDRESS)).willReturn(OWNER_ID);

        final var encoded = Bytes.wrapByteBuffer(CANCEL_AIRDROPS.encodeCall(Tuple.singleton(new Tuple[] {
            Tuple.of(
                    asHeadlongAddress(SENDER_ID.accountNum()),
                    OWNER_ACCOUNT_AS_ADDRESS,
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    0L)
        })));
        given(attempt.inputBytes()).willReturn(encoded.toArrayUnsafe());

        assertThatExceptionOfType(HandleException.class)
                .isThrownBy(() -> subject.decodeCancelAirdrop(attempt))
                .withMessage(INVALID_TOKEN_ID.protoName());
    }

    @Test
    void cancelAirdropDecoder1NFTTest() {
        // given:
        given(tokensConfig.maxAllowedPendingAirdropsToCancel()).willReturn(10);
        given(nativeOperations.getToken(NON_FUNGIBLE_TOKEN_ID)).willReturn(NON_FUNGIBLE_TOKEN);
        given(addressIdConverter.convert(asHeadlongAddress(SENDER_ID.accountNum())))
                .willReturn(SENDER_ID);
        given(addressIdConverter.convert(OWNER_ACCOUNT_AS_ADDRESS)).willReturn(OWNER_ID);

        final var encoded = Bytes.wrapByteBuffer(CANCEL_AIRDROPS.encodeCall(Tuple.singleton(new Tuple[] {
            Tuple.of(
                    asHeadlongAddress(SENDER_ID.accountNum()),
                    OWNER_ACCOUNT_AS_ADDRESS,
                    NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    1L)
        })));
        given(attempt.inputBytes()).willReturn(encoded.toArrayUnsafe());
        var expected = new ArrayList<PendingAirdropId>();
        expected.add(PendingAirdropId.newBuilder()
                .senderId(SENDER_ID)
                .receiverId(OWNER_ID)
                .nonFungibleToken(
                        NftID.newBuilder().tokenId(NON_FUNGIBLE_TOKEN_ID).serialNumber(1L))
                .build());

        // when:
        final var decoded = subject.decodeCancelAirdrop(attempt);

        // then:
        assertNotNull(decoded.tokenCancelAirdrop());
        assertEquals(expected, decoded.tokenCancelAirdrop().pendingAirdrops());
    }

    @Test
    void cancelFTAirdropHRC() {
        // given:
        given(attempt.redirectTokenId()).willReturn(FUNGIBLE_TOKEN_ID);
        given(attempt.senderId()).willReturn(SENDER_ID);

        final var encoded = Bytes.wrapByteBuffer(HRC_CANCEL_AIRDROP_FT.encodeCallWithArgs(OWNER_ACCOUNT_AS_ADDRESS));
        given(attempt.inputBytes()).willReturn(encoded.toArrayUnsafe());
        given(addressIdConverter.convert(OWNER_ACCOUNT_AS_ADDRESS)).willReturn(OWNER_ID);

        final var decoded = subject.decodeCancelAirdropFT(attempt);
        var expected = new ArrayList<PendingAirdropId>();
        expected.add(PendingAirdropId.newBuilder()
                .senderId(SENDER_ID)
                .receiverId(OWNER_ID)
                .fungibleTokenType(FUNGIBLE_TOKEN_ID)
                .build());

        // then:
        assertNotNull(decoded.tokenCancelAirdrop());
        assertEquals(expected, decoded.tokenCancelAirdrop().pendingAirdrops());
    }

    @Test
    void cancelNFTAirdropHRC() {
        // given:
        given(attempt.redirectTokenId()).willReturn(NON_FUNGIBLE_TOKEN_ID);
        given(attempt.senderId()).willReturn(SENDER_ID);

        final var encoded =
                Bytes.wrapByteBuffer(HRC_CANCEL_AIRDROP_NFT.encodeCallWithArgs(OWNER_ACCOUNT_AS_ADDRESS, 1L));
        given(attempt.inputBytes()).willReturn(encoded.toArrayUnsafe());
        given(addressIdConverter.convert(OWNER_ACCOUNT_AS_ADDRESS)).willReturn(OWNER_ID);

        final var decoded = subject.decodeCancelAirdropNFT(attempt);
        var expected = new ArrayList<PendingAirdropId>();
        expected.add(PendingAirdropId.newBuilder()
                .senderId(SENDER_ID)
                .receiverId(OWNER_ID)
                .nonFungibleToken(
                        NftID.newBuilder().tokenId(NON_FUNGIBLE_TOKEN_ID).serialNumber(1L))
                .build());
        // then:
        assertNotNull(decoded.tokenCancelAirdrop());
        assertEquals(expected, decoded.tokenCancelAirdrop().pendingAirdrops());
    }
}
