package org.hiero.hapi.fees.apis.token;

import org.hiero.hapi.fees.FeeModel;
import org.hiero.hapi.fees.FeeModelRegistry;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.NetworkFee;
import org.hiero.hapi.support.fees.NodeFee;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_MINT;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.hiero.hapi.support.fees.Extra.KEYS;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;
import static org.hiero.hapi.support.fees.Extra.STANDARD_FUNGIBLE_TOKENS;
import static org.hiero.hapi.support.fees.Extra.STANDARD_NON_FUNGIBLE_TOKENS;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TokenFeeModelTests {
    static FeeSchedule feeSchedule;

    @BeforeAll
    static void setup() {
        feeSchedule = FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(
                        makeExtraDef(Extra.BYTES, 1),
                        makeExtraDef(KEYS, 2),
                        makeExtraDef(Extra.SIGNATURES, 3),
                        makeExtraDef(STANDARD_FUNGIBLE_TOKENS, 20),
                        makeExtraDef(STANDARD_NON_FUNGIBLE_TOKENS, 200),
                        makeExtraDef(Extra.CUSTOM_FEE, 500))
                .node(NodeFee.DEFAULT
                        .copyBuilder()
                        .baseFee(1)
                        .build())
                .network(NetworkFee.DEFAULT.copyBuilder().multiplier(2).build())
                .services(makeService(
                        "Token",
                        makeServiceFee(TOKEN_CREATE, 15, makeExtraIncluded(KEYS, 1)),
                        makeServiceFee(TOKEN_MINT, 15, makeExtraIncluded(SIGNATURES, 2),
                                makeExtraIncluded(STANDARD_FUNGIBLE_TOKENS, 0),
                                makeExtraIncluded(STANDARD_NON_FUNGIBLE_TOKENS, 0)
                                )
                ))
                .build();
    }

    @Test
    void createCommonToken() {
        FeeModel model = FeeModelRegistry.lookupModel(TOKEN_CREATE);
        Map<Extra, Long> params = new HashMap<>();
        params.put(KEYS, 1L);
        FeeResult fee = model.computeFee(params, feeSchedule);
        assertEquals(15+3, fee.total());
    }

    @Test
    void createUniqueToken() {
        FeeModel model = FeeModelRegistry.lookupModel(TOKEN_CREATE);
        Map<Extra, Long> params = new HashMap<>();
        params.put(KEYS, 1L);
        FeeResult fee = model.computeFee(params, feeSchedule);
        assertEquals(15+3, fee.total());
    }

    @Test
    void mintCommonTokens() {
        FeeModel model = FeeModelRegistry.lookupModel(TOKEN_MINT);
        Map<Extra, Long> params = new HashMap<>();
        params.put(SIGNATURES, 1L);
        params.put(STANDARD_FUNGIBLE_TOKENS, 10L);
        params.put(STANDARD_NON_FUNGIBLE_TOKENS, 0L);
        FeeResult fee = model.computeFee(params, feeSchedule);
        assertEquals(15+3+10*20, fee.total());
    }

    @Test
    void testTokenNFTMintOne() {
        FeeModel model = FeeModelRegistry.lookupModel(TOKEN_MINT);
        Map<Extra, Long> params = new HashMap<>();
        params.put(SIGNATURES, 1L);
        params.put(STANDARD_FUNGIBLE_TOKENS, 0L);
        params.put(STANDARD_NON_FUNGIBLE_TOKENS, 1L);
        FeeResult fee = model.computeFee(params, feeSchedule);
        assertEquals(15+3 + 1*200, fee.total(), "Non Fungible Token Mint - 1");
    }

    @Test
    void testTokenNFTMintMultiple() {
        FeeModel model = FeeModelRegistry.lookupModel(TOKEN_MINT);
        Map<Extra, Long> params = new HashMap<>();
        params.put(SIGNATURES, 1L);
        params.put(STANDARD_NON_FUNGIBLE_TOKENS, 10L);
        params.put(STANDARD_FUNGIBLE_TOKENS, 0L);
        FeeResult fee = model.computeFee(params, feeSchedule);
        assertEquals(15+3 + 10*200, fee.total(), "Non Fungible Token Mint - 10");
    }


    @Test
    void testTokenMintWithMultipleSignatures() {
        FeeModel model = FeeModelRegistry.lookupModel(TOKEN_MINT);
        Map<Extra, Long> params = new HashMap<>();
        params.put(SIGNATURES, 6L);
        params.put(STANDARD_FUNGIBLE_TOKENS, 0L);
        params.put(STANDARD_NON_FUNGIBLE_TOKENS, 1L);
        FeeResult fee = model.computeFee(params, feeSchedule);
        assertEquals(15+3 + 1*200 + 3*4, fee.total(), "NFT mint with multiple signatures");
    }

}
