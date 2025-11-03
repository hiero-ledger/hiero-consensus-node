package com.hedera.node.app.service.token.impl.test.handlers;

import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.impl.handlers.TokenCreateHandler;
import com.hedera.node.app.service.token.impl.validators.CustomFeesValidator;
import com.hedera.node.app.service.token.impl.validators.TokenCreateValidator;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.NetworkFee;
import org.hiero.hapi.support.fees.NodeFee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CREATE;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("Token Handler Fee Tests")
public class TokenServicesFeeTests {

    @Mock
    private EntityIdFactory entityIdFactory;
    @Mock
    private CustomFeesValidator customFeesValidator;
    @Mock
    private TokenCreateValidator tokenCreateValidator;

    private TokenCreateHandler createHandler;

    @BeforeEach
    void beforeEach() {
        createHandler = new TokenCreateHandler(entityIdFactory, customFeesValidator,tokenCreateValidator);
    }


    @Test
    void testCreateToken() {
        final var txBody = TokenCreateTransactionBody.newBuilder().build();
        final var txBody2 = TransactionBody.newBuilder().tokenCreation(txBody).build();
        final var numSignatures = 1;
        final var feeContext = createMockFeeContext(txBody2,numSignatures);
        final var result = createHandler.calculateFeeResult(feeContext);
        final var expectedFee = 15;
        assertNotNull(result);
        assertEquals(expectedFee, result.total());
    }

    private FeeContext createMockFeeContext(TransactionBody txBody, int numSignatures) {
        final var feeContext = mock(FeeContext.class);
        final var feeCalculatorFactory = mock(FeeCalculatorFactory.class);
        final var feeCalculator = mock(FeeCalculator.class);
        lenient().when(feeContext.numTxnSignatures()).thenReturn(numSignatures);
        lenient().when(feeContext.body()).thenReturn(txBody);
        lenient().when(feeContext.feeCalculatorFactory()).thenReturn(feeCalculatorFactory);
        lenient().when(feeCalculatorFactory.feeCalculator(SubType.DEFAULT)).thenReturn(feeCalculator);
        lenient().when(feeCalculator.getSimpleFeesSchedule()).thenReturn(createTestFeeSchedule());
        return feeContext;
    }

    private FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT.copyBuilder()
                .node(NodeFee.DEFAULT
                        .copyBuilder()
                        .build())
                .extras(
                        makeExtraDef(Extra.BYTES, 1),
                        makeExtraDef(Extra.KEYS, 2),
                        makeExtraDef(Extra.SIGNATURES, 3),
                        makeExtraDef(Extra.CUSTOM_FEE, 500))
                .network(NetworkFee.DEFAULT.copyBuilder().multiplier(2).build())
                                    .services(makeService(
                                            "Token",
                                            makeServiceFee(TOKEN_CREATE, 15, makeExtraIncluded(Extra.KEYS,
                 1))
                                    ))

                .build();
    }
}
