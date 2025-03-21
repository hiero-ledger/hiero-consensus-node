// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.claimairdrops;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.claimairdrops.TokenClaimAirdropTranslator.CLAIM_AIRDROPS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.claimairdrops.TokenClaimAirdropTranslator.HRC_CLAIM_AIRDROP_FT;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.claimairdrops.TokenClaimAirdropTranslator.HRC_CLAIM_AIRDROP_NFT;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.claimairdrops.TokenClaimAirdropDecoder;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater.Enhancement;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.TokensConfig;
import com.swirlds.config.api.Configuration;
import java.util.ArrayList;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class TokenClaimAirdropDecoderTest {
    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private Enhancement enhancement;

    @Mock
    private HederaNativeOperations nativeOperations;

    @Mock
    private Configuration configuration;

    @Mock
    private TokensConfig tokensConfig;

    private TokenClaimAirdropDecoder subject;

    @BeforeEach
    void setup() {
        subject = new TokenClaimAirdropDecoder();

        lenient().when(attempt.addressIdConverter()).thenReturn(addressIdConverter);
    }

    @Test
    void claimAirdropDecoder1FTTest() {
        // given:
        given(attempt.configuration()).willReturn(configuration);
        given(attempt.enhancement()).willReturn(enhancement);
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(configuration.getConfigData(TokensConfig.class)).willReturn(tokensConfig);
        given(tokensConfig.maxAllowedPendingAirdropsToClaim()).willReturn(10);
        given(nativeOperations.getToken(FUNGIBLE_TOKEN_ID)).willReturn(FUNGIBLE_TOKEN);
        given(addressIdConverter.convert(asHeadlongAddress(OWNER_ID.accountNum())))
                .willReturn(OWNER_ID);
        given(addressIdConverter.convert(asHeadlongAddress(SENDER_ID.accountNum())))
                .willReturn(SENDER_ID);

        final var encoded = Bytes.wrapByteBuffer(CLAIM_AIRDROPS.encodeCall(Tuple.singleton(new Tuple[] {
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
        final var decoded = subject.decodeTokenClaimAirdrop(attempt);

        // then:
        assertNotNull(decoded.tokenClaimAirdrop());
        assertEquals(expected, decoded.tokenClaimAirdrop().pendingAirdrops());
    }

    @Test
    void failsIfPendingAirdropsAboveLimit() {
        // given:
        given(attempt.configuration()).willReturn(configuration);
        given(configuration.getConfigData(TokensConfig.class)).willReturn(tokensConfig);
        given(tokensConfig.maxAllowedPendingAirdropsToClaim()).willReturn(10);

        final var encoded = Bytes.wrapByteBuffer(CLAIM_AIRDROPS.encodeCall(Tuple.singleton(new Tuple[] {
            Tuple.of(
                    asHeadlongAddress(SENDER_ID.accountNum()),
                    OWNER_ACCOUNT_AS_ADDRESS,
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    0L),
            Tuple.of(
                    asHeadlongAddress(SENDER_ID.accountNum()),
                    OWNER_ACCOUNT_AS_ADDRESS,
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    0L),
            Tuple.of(
                    asHeadlongAddress(SENDER_ID.accountNum()),
                    OWNER_ACCOUNT_AS_ADDRESS,
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    0L),
            Tuple.of(
                    asHeadlongAddress(SENDER_ID.accountNum()),
                    OWNER_ACCOUNT_AS_ADDRESS,
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    0L),
            Tuple.of(
                    asHeadlongAddress(SENDER_ID.accountNum()),
                    OWNER_ACCOUNT_AS_ADDRESS,
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    0L),
            Tuple.of(
                    asHeadlongAddress(SENDER_ID.accountNum()),
                    OWNER_ACCOUNT_AS_ADDRESS,
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    0L),
            Tuple.of(
                    asHeadlongAddress(SENDER_ID.accountNum()),
                    OWNER_ACCOUNT_AS_ADDRESS,
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    0L),
            Tuple.of(
                    asHeadlongAddress(SENDER_ID.accountNum()),
                    OWNER_ACCOUNT_AS_ADDRESS,
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    0L),
            Tuple.of(
                    asHeadlongAddress(SENDER_ID.accountNum()),
                    OWNER_ACCOUNT_AS_ADDRESS,
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    0L),
            Tuple.of(
                    asHeadlongAddress(SENDER_ID.accountNum()),
                    OWNER_ACCOUNT_AS_ADDRESS,
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    0L),
            Tuple.of(
                    asHeadlongAddress(SENDER_ID.accountNum()),
                    OWNER_ACCOUNT_AS_ADDRESS,
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    0L)
        })));
        given(attempt.inputBytes()).willReturn(encoded.toArrayUnsafe());

        assertThrows(HandleException.class, () -> subject.decodeTokenClaimAirdrop(attempt));
    }

    @Test
    void failsIfTokenIsNull() {
        // given:
        given(attempt.configuration()).willReturn(configuration);
        given(attempt.enhancement()).willReturn(enhancement);
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(configuration.getConfigData(TokensConfig.class)).willReturn(tokensConfig);
        given(tokensConfig.maxAllowedPendingAirdropsToClaim()).willReturn(10);
        given(nativeOperations.getToken(FUNGIBLE_TOKEN_ID)).willReturn(null);
        given(addressIdConverter.convert(asHeadlongAddress(SENDER_ID.accountNum())))
                .willReturn(SENDER_ID);
        given(addressIdConverter.convert(OWNER_ACCOUNT_AS_ADDRESS)).willReturn(OWNER_ID);

        final var encoded = Bytes.wrapByteBuffer(CLAIM_AIRDROPS.encodeCall(Tuple.singleton(new Tuple[] {
            Tuple.of(
                    asHeadlongAddress(SENDER_ID.accountNum()),
                    OWNER_ACCOUNT_AS_ADDRESS,
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    0L)
        })));
        given(attempt.inputBytes()).willReturn(encoded.toArrayUnsafe());

        assertThatExceptionOfType(HandleException.class)
                .isThrownBy(() -> subject.decodeTokenClaimAirdrop(attempt))
                .withMessage(INVALID_TOKEN_ID.protoName());
    }

    @Test
    void failsIfTokenIsNullHRCFT() {
        final var encoded = Bytes.wrapByteBuffer(HRC_CLAIM_AIRDROP_FT.encodeCallWithArgs(OWNER_ACCOUNT_AS_ADDRESS));
        given(attempt.inputBytes()).willReturn(encoded.toArrayUnsafe());

        assertThatExceptionOfType(HandleException.class)
                .isThrownBy(() -> subject.decodeHrcClaimAirdropFt(attempt))
                .withMessage(INVALID_TOKEN_ID.protoName());
    }

    @Test
    void failsIfTokenIsNullHRCNFT() {
        final var encoded =
                Bytes.wrapByteBuffer(HRC_CLAIM_AIRDROP_NFT.encodeCallWithArgs(OWNER_ACCOUNT_AS_ADDRESS, 1L));
        given(attempt.inputBytes()).willReturn(encoded.toArrayUnsafe());

        assertThatExceptionOfType(HandleException.class)
                .isThrownBy(() -> subject.decodeHrcClaimAirdropNft(attempt))
                .withMessage(INVALID_TOKEN_ID.protoName());
    }

    @Test
    void claimAirdropDecoder1NFTTest() {
        // given:
        given(attempt.configuration()).willReturn(configuration);
        given(attempt.enhancement()).willReturn(enhancement);
        given(enhancement.nativeOperations()).willReturn(nativeOperations);
        given(configuration.getConfigData(TokensConfig.class)).willReturn(tokensConfig);
        given(tokensConfig.maxAllowedPendingAirdropsToClaim()).willReturn(10);
        given(nativeOperations.getToken(NON_FUNGIBLE_TOKEN_ID)).willReturn(NON_FUNGIBLE_TOKEN);
        given(addressIdConverter.convert(asHeadlongAddress(SENDER_ID.accountNum())))
                .willReturn(SENDER_ID);
        given(addressIdConverter.convert(OWNER_ACCOUNT_AS_ADDRESS)).willReturn(OWNER_ID);

        final var encoded = Bytes.wrapByteBuffer(CLAIM_AIRDROPS.encodeCall(Tuple.singleton(new Tuple[] {
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
        final var decoded = subject.decodeTokenClaimAirdrop(attempt);

        // then:
        assertNotNull(decoded.tokenClaimAirdrop());
        assertEquals(expected, decoded.tokenClaimAirdrop().pendingAirdrops());
    }

    @Test
    void claimTAirdropHRC() {
        // given:
        given(attempt.redirectTokenId()).willReturn(FUNGIBLE_TOKEN_ID);
        given(attempt.senderId()).willReturn(OWNER_ID);

        final var encoded = Bytes.wrapByteBuffer(
                HRC_CLAIM_AIRDROP_FT.encodeCallWithArgs(asHeadlongAddress(SENDER_ID.accountNum())));
        given(attempt.inputBytes()).willReturn(encoded.toArrayUnsafe());
        given(addressIdConverter.convert(asHeadlongAddress(SENDER_ID.accountNum())))
                .willReturn(SENDER_ID);

        final var decoded = subject.decodeHrcClaimAirdropFt(attempt);
        var expected = new ArrayList<PendingAirdropId>();
        expected.add(PendingAirdropId.newBuilder()
                .senderId(SENDER_ID)
                .receiverId(OWNER_ID)
                .fungibleTokenType(FUNGIBLE_TOKEN_ID)
                .build());

        // then:
        assertNotNull(decoded.tokenClaimAirdrop());
        assertEquals(expected, decoded.tokenClaimAirdrop().pendingAirdrops());
    }

    @Test
    void claimNFTAirdropHRC() {
        // given:
        given(attempt.redirectTokenId()).willReturn(NON_FUNGIBLE_TOKEN_ID);
        given(attempt.senderId()).willReturn(OWNER_ID);

        final var encoded = Bytes.wrapByteBuffer(
                HRC_CLAIM_AIRDROP_NFT.encodeCallWithArgs(asHeadlongAddress(SENDER_ID.accountNum()), 1L));
        given(attempt.inputBytes()).willReturn(encoded.toArrayUnsafe());
        given(addressIdConverter.convert(asHeadlongAddress(SENDER_ID.accountNum())))
                .willReturn(SENDER_ID);

        final var decoded = subject.decodeHrcClaimAirdropNft(attempt);
        var expected = new ArrayList<PendingAirdropId>();
        expected.add(PendingAirdropId.newBuilder()
                .senderId(SENDER_ID)
                .receiverId(OWNER_ID)
                .nonFungibleToken(
                        NftID.newBuilder().tokenId(NON_FUNGIBLE_TOKEN_ID).serialNumber(1L))
                .build());
        // then:
        assertNotNull(decoded.tokenClaimAirdrop());
        assertEquals(expected, decoded.tokenClaimAirdrop().pendingAirdrops());
    }
}
