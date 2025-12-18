package com.hedera.services.bdd.suites.fees;

import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.entityid.impl.AppEntityIdFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.workflows.standalone.TransactionExecutors;
import com.hedera.services.bdd.junit.HapiTest;
import org.junit.jupiter.api.DisplayName;

import java.util.Map;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.workflows.standalone.TransactionExecutors.TRANSACTION_EXECUTORS;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class JoshTest3 {
    @HapiTest
    @DisplayName("get the simple fee calculator")
    void doit() {
        final var overrides = Map.of("hedera.transaction.maxMemoUtf8Bytes", "101","fees.simpleFeesEnabled","true");
        // config props
        final var properties =  TransactionExecutors.Properties.newBuilder()
                .state(state)
                .appProperties(overrides)
                .build();
        final var executorComponent = TRANSACTION_EXECUTORS.newExecutorJoshBetter(properties, new AppEntityIdFactory(DEFAULT_CONFIG));
        final var calc = executorComponent.feeManager().getSimpleFeeCalculator();
        System.out.println("got the calculator " +calc);

        // calculate some fees
        final var body = TransactionBody.newBuilder()
                .tokenCreation(TokenCreateTransactionBody.newBuilder()
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .build())
                .build();
        final FeeContext feeContext = null;
        final var result = calc.calculateTxFee(body,feeContext);
        System.out.println("result is " + result);
        assertThat(result.service).isEqualTo(9999000000L);

    }
}
