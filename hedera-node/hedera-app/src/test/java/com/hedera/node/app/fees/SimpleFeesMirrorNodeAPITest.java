// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.node.app.spi.fees.ServiceFeeCalculator.EstimationMode.Intrinsic;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.consensus.ConsensusCreateTopicTransactionBody;
import com.hedera.hapi.node.consensus.ConsensusSubmitMessageTransactionBody;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.workflows.standalone.TransactionExecutors;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.hiero.hapi.fees.FeeResult;
import org.junit.jupiter.api.Test;

public class SimpleFeesMirrorNodeAPITest {

    @Test
    public void testTokenCreateIntrinsic() throws ParseException {
        final var overrides = Map.of("hedera.transaction.maxMemoUtf8Bytes", "101", "fees.simpleFeesEnabled", "true");
        // bring up the full state
        final State state = FakeGenesisState.make(overrides);
        final var properties = TransactionExecutors.Properties.newBuilder()
                .state(state)
                .appProperties(overrides)
                .build();

        // make the calculator
        final StandaloneFeeCalculator calc = new StandaloneFeeCalculatorImpl(state, properties);

        // make an example transaction
        final var body = TransactionBody.newBuilder()
                .tokenCreation(TokenCreateTransactionBody.newBuilder()
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .build())
                .build();

        final var signedTx = SignedTransaction.newBuilder()
                .bodyBytes(TransactionBody.PROTOBUF.toBytes(body))
                .build();

        final Transaction txn = Transaction.newBuilder()
                .signedTransactionBytes(SignedTransaction.PROTOBUF.toBytes(signedTx))
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
    private StandaloneFeeCalculator setupCalculator() {
        // configure overrides
        final var overrides = Map.of("hedera.transaction.maxMemoUtf8Bytes", "101", "fees.simpleFeesEnabled", "true");
        // bring up the full state
        final State state = FakeGenesisState.make(overrides);

        final var properties = TransactionExecutors.Properties.newBuilder()
                .state(state)
                .appProperties(overrides)
                .build();

        // make the calculator
        final StandaloneFeeCalculator calc = new StandaloneFeeCalculatorImpl(state, properties);
        return calc;
    }

    @Test
    public void testSubmitMessageIntrinsicPasses() throws ParseException {
        final StandaloneFeeCalculator calc = setupCalculator();

        final long topicEntityNum = 1L;
        final TopicID topicId = TopicID.newBuilder().topicNum(topicEntityNum).build();
        final var body = TransactionBody.newBuilder()
                .consensusSubmitMessage(ConsensusSubmitMessageTransactionBody.newBuilder()
                        .topicID(topicId)
                        .message(Bytes.wrap("some message"))
                        .build())
                .build();
        final var sigMap = SignatureMap.newBuilder().build();
        final var signedTx = SignedTransaction.newBuilder()
                .bodyBytes(TransactionBody.PROTOBUF.toBytes(body))
                .sigMap(sigMap)
                .build();
        final Transaction txn = Transaction.newBuilder()
                .signedTransactionBytes(SignedTransaction.PROTOBUF.toBytes(signedTx)).build();

        final FeeResult result = calc.calculate(txn, Intrinsic);
        assertThat(result.service).isEqualTo(0L);
//        System.out.println("JSON is \n" + feeResultToJson(result));
    }

    @Test
    public void testCreateTopic() throws ParseException {
        final StandaloneFeeCalculator calc = setupCalculator();
        final var body = TransactionBody.newBuilder()
                .consensusCreateTopic(ConsensusCreateTopicTransactionBody.newBuilder()
                        .memo("sometopicname").build()
                ).build();
        final Transaction txn = Transaction.newBuilder().body(body).build();
        //0.01000
        final FeeResult result = calc.calculate(txn, Intrinsic);
        System.out.println("fee is " + result);
        final var TINY_CENTS = 100_000_000L;
        assertThat(result.total()).isEqualTo(1 * TINY_CENTS); //0.01 USD
    }

    @Test
    public void testSubmitMessage() throws ParseException {
        final StandaloneFeeCalculator calc = setupCalculator();
        //0.01000
        final long topicEntityNum = 1L;
        final TopicID topicId =
                TopicID.newBuilder().topicNum(topicEntityNum).build();
        final var body = TransactionBody.newBuilder()
                .consensusSubmitMessage(ConsensusSubmitMessageTransactionBody.newBuilder()
                        .topicID(topicId)
                        .message(Bytes.wrap("some message"))
                        .build())
                .build();
        final Transaction txn = Transaction.newBuilder().body(body).build();
        final FeeResult result = calc.calculate(txn, Intrinsic);
        assertThat(result.service).isEqualTo(0L);
    }

    @Test
    public void testSignedTransaction() throws ParseException {
        // configure overrides
        final var overrides = Map.of("hedera.transaction.maxMemoUtf8Bytes", "101", "fees.simpleFeesEnabled", "true");
        // bring up the full state
        final State state = FakeGenesisState.make(overrides);

        final var properties = TransactionExecutors.Properties.newBuilder()
                .state(state)
                .appProperties(overrides)
                .build();

        // make the calculator
        final StandaloneFeeCalculator calc = new StandaloneFeeCalculatorImpl(state, properties);

        // make an example transaction
        final long topicEntityNum = 1L;
        final TopicID topicId = TopicID.newBuilder().topicNum(topicEntityNum).build();

        final var body = TransactionBody.newBuilder()
                .consensusSubmitMessage(ConsensusSubmitMessageTransactionBody.newBuilder()
                        .topicID(topicId)
                        .message(Bytes.wrap("some message"))
                        .build())
                .build();
        final SignaturePair pair = SignaturePair.newBuilder()
                .pubKeyPrefix(Bytes.wrap("prefix"))
                .ed25519(Bytes.wrap("signature"))
                .build();
        final var sigMap = SignatureMap.newBuilder()
                .sigPair(pair)
                .build();
        final var signedTx = SignedTransaction.newBuilder()
                .bodyBytes(TransactionBody.PROTOBUF.toBytes(body))
                .sigMap(sigMap)
                .build();

        final Transaction txn = Transaction.newBuilder()
                .signedTransactionBytes(SignedTransaction.PROTOBUF.toBytes(signedTx)).build();

        final FeeResult result = calc.calculate(txn, Intrinsic);
        assertThat(result.service).isEqualTo(0L);
        System.out.println("JSON is \n" + feeResultToJson(result));
    }
}
