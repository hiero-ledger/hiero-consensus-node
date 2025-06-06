// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.airdrops;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_RECEIVING_NODE_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_REFERENCE_LIST_SIZE_LIMIT_EXCEEDED;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.airdrops.TokenAirdropTranslator.TOKEN_AIRDROP;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ACCOUNT_AS_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SYSTEM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asHeadlongAddress;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.token.TokenAirdropTransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.airdrops.TokenAirdropDecoder;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.TokensConfig;
import com.swirlds.config.api.Configuration;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class TokenAirdropDecoderTest {

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private Configuration configuration;

    @Mock
    private TokensConfig tokensConfig;

    @Mock
    private LedgerConfig ledgerConfig;

    @Mock
    private HederaNativeOperations hederaNativeOperations;

    private TokenAirdropDecoder subject;

    private final TokenAirdropTransactionBody tokenAirdrop = TokenAirdropTransactionBody.newBuilder()
            .tokenTransfers(TokenTransferList.newBuilder()
                    .token(FUNGIBLE_TOKEN_ID)
                    .transfers(
                            AccountAmount.newBuilder()
                                    .accountID(SENDER_ID)
                                    .amount(-10)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(OWNER_ID)
                                    .amount(10)
                                    .build())
                    .build())
            .build();

    private final TokenAirdropTransactionBody nftAirdrop = TokenAirdropTransactionBody.newBuilder()
            .tokenTransfers(TokenTransferList.newBuilder()
                    .token(NON_FUNGIBLE_TOKEN_ID)
                    .nftTransfers(nftTransfer(1))
                    .build())
            .build();

    private final TokenAirdropTransactionBody nftAirdrops = TokenAirdropTransactionBody.newBuilder()
            .tokenTransfers(TokenTransferList.newBuilder()
                    .token(NON_FUNGIBLE_TOKEN_ID)
                    .nftTransfers(nftTransfer(1), nftTransfer(2), nftTransfer(3))
                    .build())
            .build();

    @BeforeEach
    void setUp() {
        subject = new TokenAirdropDecoder();
        lenient()
                .when(addressIdConverter.convert(asHeadlongAddress(SENDER_ID.accountNum())))
                .thenReturn(SENDER_ID);
        lenient().when(addressIdConverter.convert(OWNER_ACCOUNT_AS_ADDRESS)).thenReturn(OWNER_ID);
    }

    @Test
    void tokenAirdropDecoderWorks() {
        final var tuple = new Tuple[] {
            Tuple.of(
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    new Tuple[] {
                        Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), -10L, false),
                        Tuple.of(OWNER_ACCOUNT_AS_ADDRESS, 10L, false)
                    },
                    new Tuple[] {})
        };
        final var encoded = Bytes.wrapByteBuffer(TOKEN_AIRDROP.encodeCall(Tuple.singleton(tuple)));
        given(attempt.inputBytes()).willReturn(encoded.toArrayUnsafe());
        given(attempt.configuration()).willReturn(configuration);
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(configuration.getConfigData(LedgerConfig.class)).willReturn(ledgerConfig);
        given(ledgerConfig.tokenTransfersMaxLen()).willReturn(10);
        given(ledgerConfig.nftTransfersMaxLen()).willReturn(10);
        final var body = subject.decodeAirdrop(attempt);
        assertNotNull(body);
        assertNotNull(body.tokenAirdrop());
        assertNotNull(body.tokenAirdrop().tokenTransfers());
        assertEquals(tokenAirdrop, body.tokenAirdrop());
    }

    @Test
    void tokenAirdropDecoderFailsIfReceiverIsSystemAcc() {
        final var tuple = new Tuple[] {
            Tuple.of(
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    new Tuple[] {
                        Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), -10L, false),
                        Tuple.of(asHeadlongAddress(SYSTEM_ADDRESS), 10L, false)
                    },
                    new Tuple[] {})
        };
        final var encoded = Bytes.wrapByteBuffer(TOKEN_AIRDROP.encodeCall(Tuple.singleton(tuple)));
        given(attempt.inputBytes()).willReturn(encoded.toArrayUnsafe());
        given(attempt.configuration()).willReturn(configuration);
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(configuration.getConfigData(LedgerConfig.class)).willReturn(ledgerConfig);
        given(ledgerConfig.tokenTransfersMaxLen()).willReturn(10);
        given(ledgerConfig.nftTransfersMaxLen()).willReturn(10);
        given(addressIdConverter.convert(asHeadlongAddress(SYSTEM_ADDRESS)))
                .willReturn(AccountID.newBuilder().accountNum(750).build());
        assertThatExceptionOfType(HandleException.class)
                .isThrownBy(() -> subject.decodeAirdrop(attempt))
                .withMessage(INVALID_RECEIVING_NODE_ACCOUNT.protoName());
    }

    @Test
    void tokenAirdropDecoderFailsIfAirdropExceedsLimits() {
        final var tuple = new Tuple[] {
            Tuple.of(
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    new Tuple[] {
                        Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), -10L, false),
                        Tuple.of(OWNER_ACCOUNT_AS_ADDRESS, 10L, false)
                    },
                    new Tuple[] {}),
            Tuple.of(
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    new Tuple[] {
                        Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), -10L, false),
                        Tuple.of(OWNER_ACCOUNT_AS_ADDRESS, 10L, false)
                    },
                    new Tuple[] {}),
            Tuple.of(
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    new Tuple[] {
                        Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), -10L, false),
                        Tuple.of(OWNER_ACCOUNT_AS_ADDRESS, 10L, false)
                    },
                    new Tuple[] {}),
            Tuple.of(
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    new Tuple[] {
                        Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), -10L, false),
                        Tuple.of(OWNER_ACCOUNT_AS_ADDRESS, 10L, false)
                    },
                    new Tuple[] {}),
            Tuple.of(
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    new Tuple[] {
                        Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), -10L, false),
                        Tuple.of(OWNER_ACCOUNT_AS_ADDRESS, 10L, false)
                    },
                    new Tuple[] {}),
            Tuple.of(
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    new Tuple[] {
                        Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), -10L, false),
                        Tuple.of(OWNER_ACCOUNT_AS_ADDRESS, 10L, false)
                    },
                    new Tuple[] {}),
            Tuple.of(
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    new Tuple[] {
                        Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), -10L, false),
                        Tuple.of(OWNER_ACCOUNT_AS_ADDRESS, 10L, false)
                    },
                    new Tuple[] {}),
            Tuple.of(
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    new Tuple[] {
                        Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), -10L, false),
                        Tuple.of(OWNER_ACCOUNT_AS_ADDRESS, 10L, false)
                    },
                    new Tuple[] {}),
            Tuple.of(
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    new Tuple[] {
                        Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), -10L, false),
                        Tuple.of(OWNER_ACCOUNT_AS_ADDRESS, 10L, false)
                    },
                    new Tuple[] {}),
            Tuple.of(
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    new Tuple[] {
                        Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), -10L, false),
                        Tuple.of(OWNER_ACCOUNT_AS_ADDRESS, 10L, false)
                    },
                    new Tuple[] {}),
            Tuple.of(
                    FUNGIBLE_TOKEN_HEADLONG_ADDRESS,
                    new Tuple[] {
                        Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), -10L, false),
                        Tuple.of(OWNER_ACCOUNT_AS_ADDRESS, 10L, false)
                    },
                    new Tuple[] {}),
        };
        final var encoded = Bytes.wrapByteBuffer(TOKEN_AIRDROP.encodeCall(Tuple.singleton(tuple)));
        given(attempt.inputBytes()).willReturn(encoded.toArrayUnsafe());
        given(attempt.configuration()).willReturn(configuration);
        given(configuration.getConfigData(LedgerConfig.class)).willReturn(ledgerConfig);
        given(ledgerConfig.tokenTransfersMaxLen()).willReturn(10);
        given(ledgerConfig.nftTransfersMaxLen()).willReturn(10);
        assertThatExceptionOfType(HandleException.class)
                .isThrownBy(() -> subject.decodeAirdrop(attempt))
                .withMessage(TOKEN_REFERENCE_LIST_SIZE_LIMIT_EXCEEDED.protoName());
    }

    @Test
    void tokenAirdropDecoderWorksForNFT() {
        final var tuple = new Tuple[] {
            Tuple.of(NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, new Tuple[] {}, new Tuple[] {
                Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), OWNER_ACCOUNT_AS_ADDRESS, 1L, false)
            })
        };
        final var encoded = Bytes.wrapByteBuffer(TOKEN_AIRDROP.encodeCall(Tuple.singleton(tuple)));
        given(attempt.inputBytes()).willReturn(encoded.toArrayUnsafe());
        given(attempt.configuration()).willReturn(configuration);
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(configuration.getConfigData(LedgerConfig.class)).willReturn(ledgerConfig);
        given(ledgerConfig.tokenTransfersMaxLen()).willReturn(10);
        given(ledgerConfig.nftTransfersMaxLen()).willReturn(10);
        final var body = subject.decodeAirdrop(attempt);
        assertNotNull(body);
        assertNotNull(body.tokenAirdrop());
        assertNotNull(body.tokenAirdrop().tokenTransfers());
        assertEquals(nftAirdrop, body.tokenAirdrop());
    }

    @Test
    void tokenAirdropDecoderWorksForMultipleNFTs() {
        final var tuple = new Tuple[] {
            Tuple.of(NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, new Tuple[] {}, new Tuple[] {
                Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), OWNER_ACCOUNT_AS_ADDRESS, 1L, false),
                Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), OWNER_ACCOUNT_AS_ADDRESS, 2L, false),
                Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), OWNER_ACCOUNT_AS_ADDRESS, 3L, false)
            })
        };
        final var encoded = Bytes.wrapByteBuffer(TOKEN_AIRDROP.encodeCall(Tuple.singleton(tuple)));
        given(attempt.inputBytes()).willReturn(encoded.toArrayUnsafe());
        given(attempt.configuration()).willReturn(configuration);
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(attempt.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(configuration.getConfigData(LedgerConfig.class)).willReturn(ledgerConfig);
        given(ledgerConfig.tokenTransfersMaxLen()).willReturn(10);
        given(ledgerConfig.nftTransfersMaxLen()).willReturn(10);
        final var body = subject.decodeAirdrop(attempt);
        assertNotNull(body);
        assertNotNull(body.tokenAirdrop());
        assertNotNull(body.tokenAirdrop().tokenTransfers());
        assertEquals(nftAirdrops, body.tokenAirdrop());
    }

    @Test
    void tokenAirdropDecoderForNFTFailsIfNftExceedLimits() {
        final var tuple = new Tuple[] {
            Tuple.of(NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS, new Tuple[] {}, new Tuple[] {
                Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), OWNER_ACCOUNT_AS_ADDRESS, 1L, false),
                Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), OWNER_ACCOUNT_AS_ADDRESS, 2L, false),
                Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), OWNER_ACCOUNT_AS_ADDRESS, 3L, false),
                Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), OWNER_ACCOUNT_AS_ADDRESS, 4L, false),
                Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), OWNER_ACCOUNT_AS_ADDRESS, 5L, false),
                Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), OWNER_ACCOUNT_AS_ADDRESS, 6L, false),
                Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), OWNER_ACCOUNT_AS_ADDRESS, 7L, false),
                Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), OWNER_ACCOUNT_AS_ADDRESS, 8L, false),
                Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), OWNER_ACCOUNT_AS_ADDRESS, 9L, false),
                Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), OWNER_ACCOUNT_AS_ADDRESS, 10L, false),
                Tuple.of(asHeadlongAddress(SENDER_ID.accountNum()), OWNER_ACCOUNT_AS_ADDRESS, 11L, false)
            })
        };
        final var encoded = Bytes.wrapByteBuffer(TOKEN_AIRDROP.encodeCall(Tuple.singleton(tuple)));
        given(attempt.inputBytes()).willReturn(encoded.toArrayUnsafe());
        given(attempt.configuration()).willReturn(configuration);
        given(configuration.getConfigData(LedgerConfig.class)).willReturn(ledgerConfig);
        given(ledgerConfig.tokenTransfersMaxLen()).willReturn(10);
        given(ledgerConfig.nftTransfersMaxLen()).willReturn(10);
        assertThatExceptionOfType(HandleException.class)
                .isThrownBy(() -> subject.decodeAirdrop(attempt))
                .withMessage(TOKEN_REFERENCE_LIST_SIZE_LIMIT_EXCEEDED.protoName());
    }

    private NftTransfer nftTransfer(final long serial) {
        return NftTransfer.newBuilder()
                .senderAccountID(SENDER_ID)
                .receiverAccountID(OWNER_ID)
                .serialNumber(serial)
                .build();
    }
}
