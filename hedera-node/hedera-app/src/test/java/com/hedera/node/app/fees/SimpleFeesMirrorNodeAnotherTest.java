package com.hedera.node.app.fees;

import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.consensus.ConsensusSubmitMessageTransactionBody;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.entityid.impl.AppEntityIdFactory;
import com.hedera.node.app.spi.fees.SimpleFeeCalculator;
import com.hedera.node.app.workflows.standalone.TransactionExecutors;
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import org.hiero.hapi.fees.FeeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.workflows.standalone.TransactionExecutors.TRANSACTION_EXECUTORS;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;


public class SimpleFeesMirrorNodeAnotherTest {

    public enum FeeMode {
        Stateful, Intrinsic
    }

    public interface FeeCalculator {
        FeeResult calculate(Transaction transaction, FeeMode mode);
    }

    @Test
    public void testTokenCreateIntrinsic() {
        final var overrides = Map.of("hedera.transaction.maxMemoUtf8Bytes", "101","fees.simpleFeesEnabled","true");
        // bring up the full state
        final State state = FakeGenesisState.make(overrides);
        final var properties =  TransactionExecutors.Properties.newBuilder()
                .state(state)
                .appProperties(overrides)
                .build();

        // make the calculator
        final FeeCalculator calc = makeMirrorNodeCalculator(state,properties);

        // make an example transaction
        final var body = TransactionBody.newBuilder()
                .tokenCreation(TokenCreateTransactionBody.newBuilder()
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .build())
                .build();
        final Transaction txn = Transaction.newBuilder().body(body).build();

        final FeeResult result = calc.calculate(txn,FeeMode.Intrinsic);
        assertThat(result.service).isEqualTo(9999000000L);
    }

    @Test
    public void testSubmitMessageIntrinsicPasses() {
        // configure overrides
        final var overrides = Map.of("hedera.transaction.maxMemoUtf8Bytes", "101","fees.simpleFeesEnabled","true");
        // bring up the full state
        final State state = FakeGenesisState.make(overrides);

        final var properties =  TransactionExecutors.Properties.newBuilder()
                .state(state)
                .appProperties(overrides)
                .build();

        // make the calculator
        final FeeCalculator calc =  makeMirrorNodeCalculator(state,properties);

        // make an example transaction
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

        final FeeResult result = calc.calculate(txn,FeeMode.Intrinsic);
        assertThat(result.service).isEqualTo(0L);
    }

    @Test
    public void testSubmitMessageStatefulFails() {
        final var overrides = Map.of("hedera.transaction.maxMemoUtf8Bytes", "101","fees.simpleFeesEnabled","true");
        // bring up the full state
        final State state = FakeGenesisState.make(overrides);

        final var properties =  TransactionExecutors.Properties.newBuilder()
                .state(state)
                .appProperties(overrides)
                .build();

        // make the calculator
        final FeeCalculator calc =  makeMirrorNodeCalculator(state,properties);

        // make an example transaction
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

        assertThrows(NullPointerException.class, () -> calc.calculate(txn,FeeMode.Stateful));
    }
    private FeeCalculator makeMirrorNodeCalculator(State state, TransactionExecutors.Properties properties) {
        return new TestFeeCalcImpl(state,properties);
    }


    @ExtendWith(MockitoExtension.class)
    class TestFeeCalcImpl implements FeeCalculator {
        @Mock
        private FeeContextImpl feeContext;

        private final SimpleFeeCalculator calc;

        public TestFeeCalcImpl(State state, TransactionExecutors.Properties properties) {
            MockitoAnnotations.openMocks(this);
//            when(feeContext.numTxnSignatures()).thenReturn(5);
            // make an entity id factory
            final var entityIdFactory = new AppEntityIdFactory(DEFAULT_CONFIG);
            // load a new executor component
            final var executor = TRANSACTION_EXECUTORS.newExecutorComponent(properties.state()
                    ,properties.appProperties(),
                    properties.customTracerBinding(),
                    properties.customOps(),
                    entityIdFactory);
            // init
            executor.stateNetworkInfo().initFrom(properties.state());
            executor.initializer().initialize(properties.state(), StreamMode.BOTH);

            this.calc = executor.feeManager().getSimpleFeeCalculator();
        }

        @Override
        public FeeResult calculate(Transaction transaction, FeeMode mode) {
            var body = transaction.bodyOrThrow();
            if(mode == FeeMode.Stateful) {
                when(feeContext.isStatefulEstimationMode()).thenReturn(true);
            }
            System.out.println("using fee context " + feeContext.isStatefulEstimationMode());
            final var result = calc.calculateTxFee(body,feeContext);
            System.out.println("result is " + result);
            return result;
        }
    }
}
