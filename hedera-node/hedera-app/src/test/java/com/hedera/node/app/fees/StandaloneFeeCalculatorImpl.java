package com.hedera.node.app.fees;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.entityid.impl.AppEntityIdFactory;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeCalculator;
import com.hedera.node.app.workflows.standalone.TransactionExecutors;
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import org.hiero.hapi.fees.FeeResult;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.workflows.standalone.TransactionExecutors.TRANSACTION_EXECUTORS;

public class StandaloneFeeCalculatorImpl implements StandaloneFeeCalculator {
    public static class TestFeeContextImpl implements FeeContext {

        private int _numTxnSignatures;

        public TestFeeContextImpl() {
            this._numTxnSignatures = 0;
        }


        @Override
        public @NonNull AccountID payer() {
            return null;
        }

        @Override
        public @NonNull TransactionBody body() {
            return null;
        }

        @Override
        public @NonNull FeeCalculatorFactory feeCalculatorFactory() {
            return null;
        }

        @Override
        public SimpleFeeCalculator getSimpleFeeCalculator() {
            return null;
        }

        @Override
        public @NonNull <T> T readableStore(@NonNull Class<T> storeInterface) {
            return null;
        }

        @Override
        public @NonNull Configuration configuration() {
            return null;
        }

        @Override
        public @Nullable Authorizer authorizer() {
            return null;
        }

        @Override
        public int numTxnSignatures() {
            return this._numTxnSignatures;
        }

        @Override
        public Fees dispatchComputeFees(@NonNull TransactionBody txBody, @NonNull AccountID syntheticPayerId) {
            return null;
        }

        @Override
        public ExchangeRate activeRate() {
            return null;
        }

        @Override
        public long getGasPriceInTinycents() {
            return 0;
        }

        public void setNumTxnSignatures(int sigcount) {
            this._numTxnSignatures = sigcount;
        }
    }

    private TestFeeContextImpl feeContext = new TestFeeContextImpl();

    private final SimpleFeeCalculator calc;

    public StandaloneFeeCalculatorImpl(State state, TransactionExecutors.Properties properties) {
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
        executor.stateNetworkInfo().initFrom(state);
        executor.initializer().initialize(state, StreamMode.BOTH);

        // return the calculator
        this.calc = executor.feeManager().getSimpleFeeCalculator();
    }

    @Override
    public FeeResult calculate(Transaction transaction, ServiceFeeCalculator.EstimationMode mode) throws ParseException {
        final SignedTransaction signedTransaction = SignedTransaction.PROTOBUF.parse(
                BufferedData.wrap(transaction.signedTransactionBytes().toByteArray()));
        if (signedTransaction.hasSigMap()) {
            final var sigMap = signedTransaction.sigMapOrThrow();
            feeContext.setNumTxnSignatures(sigMap.sigPair().size());
        } else {
            feeContext.setNumTxnSignatures(0);
        }
        final TransactionBody transactionBody = TransactionBody.PROTOBUF.parse(
                BufferedData.wrap(signedTransaction.bodyBytes().toByteArray()));
        return calc.calculateTxFee(transactionBody, feeContext, ServiceFeeCalculator.EstimationMode.Intrinsic);
    }
}
