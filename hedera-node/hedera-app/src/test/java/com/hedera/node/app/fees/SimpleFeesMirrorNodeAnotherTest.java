// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.spi.fees.ServiceFeeCalculator.EstimationMode.Intrinsic;
import static com.hedera.node.app.workflows.standalone.TransactionExecutors.TRANSACTION_EXECUTORS;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.consensus.ConsensusSubmitMessageTransactionBody;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.entityid.impl.AppEntityIdFactory;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeCalculator;
import com.hedera.node.app.workflows.standalone.TransactionExecutors;
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.hiero.hapi.fees.FeeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;

public class SimpleFeesMirrorNodeAnotherTest {

    public interface StandaloneFeeCalculator {
        FeeResult calculate(Transaction transaction, ServiceFeeCalculator.EstimationMode mode);
    }

    @Test
    public void testTokenCreateIntrinsic() {
        final var overrides = Map.of("hedera.transaction.maxMemoUtf8Bytes", "101", "fees.simpleFeesEnabled", "true");
        // bring up the full state
        final State state = FakeGenesisState.make(overrides);
        final var properties = TransactionExecutors.Properties.newBuilder()
                .state(state)
                .appProperties(overrides)
                .build();

        // make the calculator
        final StandaloneFeeCalculator calc = makeStandaloneFeeCalculator(state, properties);

        // make an example transaction
        final var body = TransactionBody.newBuilder()
                .tokenCreation(TokenCreateTransactionBody.newBuilder()
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .build())
                .build();

        final var signedTx = SignedTransaction.newBuilder()
                .bodyBytes(body)
                .build();

        final Transaction txn = Transaction.newBuilder()
                .signedTransactionBytes(signedTx)
                .build();

        final FeeResult result = calc.calculate(txn, Intrinsic);
        assertThat(result.service).isEqualTo(9999000000L);
        System.out.println("JSON is \n" + feeResultToJson(result));
    }

    class JsonBuilder {
        private final List<String> output;
        private int inset;

        public JsonBuilder() {
            this.output = new ArrayList<>();
            this.inset = 0;
        }
        private void indent() {
            this.inset += 1;
        }


        private String tab() {
            var out = new StringBuilder();
            out.append("  ".repeat(Math.max(0, this.inset)));
            return out.toString();
        }

        @Override
        public String toString() {
            var out = new StringBuilder();
            for(var line : this.output) {
                out.append(line+"\n");
            }
            return out.toString();
        }


        private void outdent() {
            this.inset -= 1;
        }

        public void openObject() {
            this.output.add(this.tab()+"{");
            this.indent();
        }
        public void closeObject() {
            this.outdent();
            this.output.add(this.tab()+"}");
        }
        public void keyValue(String key, String value) {
            this.output.add(this.tab()+"\""+key+"\": \""+value+"\"");
        }
        public void keyValue(String key, long value) {
            this.output.add(this.tab()+"\""+key+"\":"+value);
        }

        public void openKeyObject(String key) {
            this.output.add(this.tab()+"\""+key+"\": {");
            this.indent();
        }
        public void openKeyArray(String key) {
            this.output.add(this.tab()+"\""+key+"\": [");
            this.indent();
        }
        public void closeKeyObject() {
            this.outdent();
            this.output.add(this.tab()+"}");
        }
        public void closeKeyArray() {
            this.outdent();
            this.output.add(this.tab()+"]");
        }

    }
    private String feeResultToJson(FeeResult result) {
        System.out.println("result is " + result);
        JsonBuilder json = new JsonBuilder();
        json.openObject();


        json.openKeyObject("node");
        json.openKeyArray("extras");
        for(FeeResult.FeeDetail detail : result.nodeDetails) {
            json.keyValue("name",detail.name);
            json.keyValue("count",detail.count);
            json.keyValue("fee",detail.fee);
        }
        json.closeKeyArray();
        json.keyValue("subtotal",result.node);
        json.closeKeyObject();


        json.openKeyObject("network");
        json.openKeyArray("extras");
        for(FeeResult.FeeDetail detail : result.networkDetails) {
            json.keyValue("name",detail.name);
            json.keyValue("count",detail.count);
            json.keyValue("fee",detail.fee);
        }
        json.closeKeyArray();
        json.keyValue("subtotal",result.network);
        json.closeKeyObject();


        json.openKeyObject("service");
        json.keyValue("baseFee",result.service);
        json.openKeyArray("extras");
        for(FeeResult.FeeDetail detail : result.serviceDetails) {
            json.keyValue("name",detail.name);
            json.keyValue("count",detail.count);
            json.keyValue("fee",detail.fee);
        }
        json.closeKeyArray();
        json.closeKeyObject();


        json.keyValue("total",result.total());
        json.closeObject();
        return json.toString();
        /*
        {
  "network": {
    "multiplier": 9,
    "subtotal": 900000
  },
  "node": {
    "baseFee": 100000,
    "extras": [
      {
        "charged": 0,
        "count": 150,
        "fee_per_unit": 10000,
        "included": 1024,
        "name": "Bytes",
        "subtotal": 0
      },
      {
        "charged": 1,
        "count": 2,
        "fee_per_unit": 100000,
        "included": 1,
        "name": "Signatures",
        "subtotal": 0
      }
    ]
  },
  "notes": [],
  "service": {
    "baseFee": 499000000,
    "extras": [
      {
        "charged": 0,
        "count": 1,
        "fee_per_unit": 10000000,
        "included": 1,
        "name": "Keys",
        "subtotal": 0
      }
    ]
  },
  "total": 500000000
}

         */
    }

    @Test
    public void testSubmitMessageIntrinsicPasses() {
        // configure overrides
        final var overrides = Map.of("hedera.transaction.maxMemoUtf8Bytes", "101", "fees.simpleFeesEnabled", "true");
        // bring up the full state
        final State state = FakeGenesisState.make(overrides);

        final var properties = TransactionExecutors.Properties.newBuilder()
                .state(state)
                .appProperties(overrides)
                .build();

        // make the calculator
        final StandaloneFeeCalculator calc = makeStandaloneFeeCalculator(state, properties);

        // make an example transaction
        final long topicEntityNum = 1L;
        final TopicID topicId = TopicID.newBuilder().topicNum(topicEntityNum).build();

        final var body = TransactionBody.newBuilder()
                .consensusSubmitMessage(ConsensusSubmitMessageTransactionBody.newBuilder()
                        .topicID(topicId)
                        .message(Bytes.wrap("some message"))
                        .build())
                .build();
        final var signedTx = SignedTransaction.newBuilder()
                .bodyBytes(body)
                .build();

        final Transaction txn = Transaction.newBuilder()
                .signedTransactionBytes(signedTx).build();

        final FeeResult result = calc.calculate(txn, Intrinsic);
        assertThat(result.service).isEqualTo(0L);
        System.out.println("JSON is \n" + feeResultToJson(result));
    }

    //    @Test
    //    public void testSubmitMessageStatefulFails() {
    //        final var overrides = Map.of("hedera.transaction.maxMemoUtf8Bytes",
    // "101","fees.simpleFeesEnabled","true");
    //        // bring up the full state
    //        final State state = FakeGenesisState.make(overrides);
    //
    //        final var properties =  TransactionExecutors.Properties.newBuilder()
    //                .state(state)
    //                .appProperties(overrides)
    //                .build();
    //
    //        // make the calculator
    //        final FeeCalculator calc =  makeMirrorNodeCalculator(state,properties);
    //
    //        // make an example transaction
    //        final long topicEntityNum = 1L;
    //        final TopicID topicId =
    //                TopicID.newBuilder().topicNum(topicEntityNum).build();
    //
    //        final var body = TransactionBody.newBuilder()
    //                .consensusSubmitMessage(ConsensusSubmitMessageTransactionBody.newBuilder()
    //                        .topicID(topicId)
    //                        .message(Bytes.wrap("some message"))
    //                        .build())
    //                .build();
    //        final Transaction txn = Transaction.newBuilder().body(body).build();
    //
    //        assertThrows(NullPointerException.class, () -> calc.calculate(txn,
    // ServiceFeeCalculator.EstimationMode.Stateful));
    //    }
    public static StandaloneFeeCalculator makeStandaloneFeeCalculator(State state, TransactionExecutors.Properties properties) {
        return new TestFeeCalcImpl(state, properties);
    }

    @ExtendWith(MockitoExtension.class)
    public static class TestFeeCalcImpl implements StandaloneFeeCalculator {
        @Mock
        private FeeContextImpl feeContext;

        private final SimpleFeeCalculator calc;

        public TestFeeCalcImpl(State state, TransactionExecutors.Properties properties) {
            MockitoAnnotations.openMocks(this);
            //            when(feeContext.numTxnSignatures()).thenReturn(5);
            // make an entity id factory
            final var entityIdFactory = new AppEntityIdFactory(DEFAULT_CONFIG);
            // load a new executor component
            final var executor = TRANSACTION_EXECUTORS.newExecutorComponent(
                    properties.state(),
                    properties.appProperties(),
                    properties.customTracerBinding(),
                    properties.customOps(),
                    entityIdFactory);
            // init
            executor.stateNetworkInfo().initFrom(properties.state());
            executor.initializer().initialize(properties.state(), StreamMode.BOTH);

            this.calc = executor.feeManager().getSimpleFeeCalculator();
        }

        @Override
        public FeeResult calculate(Transaction transaction, ServiceFeeCalculator.EstimationMode mode) {
            var body = transaction.bodyOrThrow();
            return calc.calculateTxFee(body, feeContext, ServiceFeeCalculator.EstimationMode.Intrinsic);
        }
    }
}
