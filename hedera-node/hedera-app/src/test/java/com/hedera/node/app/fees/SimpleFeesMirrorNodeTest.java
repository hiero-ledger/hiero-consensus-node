package com.hedera.node.app.fees;

import com.hedera.hapi.node.consensus.ConsensusCreateTopicTransactionBody;
import org.junit.jupiter.api.Test;

import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.consensus.ConsensusSubmitMessageTransactionBody;
import com.hedera.node.app.service.consensus.impl.ConsensusServiceImpl;
import com.hedera.node.app.service.schedule.impl.ScheduleServiceImpl;
import com.hedera.node.app.service.token.impl.TokenServiceImpl;
import org.hiero.hapi.support.fees.FeeSchedule;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.QueryFeeCalculator;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeCalculatorImpl;
import com.hedera.node.app.service.file.impl.FileServiceImpl;

import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hiero.hapi.fees.FeeResult;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static org.hiero.hapi.fees.FeeScheduleUtils.isValid;

/*

Here's how calculating fees works.  Every service has a fee calculator class which implements the ServiceFeeCalculator interface.

There is a SimpleFeeCalculator interface with one implementation.  It needs a loaded fee schedule and a list of calculators
for each service you want to calculate with. From this interface you can call calculateTxFee(body,feeContext).
This will return a FeeResult object in USD, which you can convert to hbar using the FeeUtils.feeResultToFees() method
and the current exchange rate.



Questions:

Where is the best place to put this?

How do we properly import the service impls? ConsensusServiceImpl seems to be exported already but
others like CryptoServiceImpl are not. And some impls need a bunch of parameters to init. Instead
let's have static lists of fee calculators on each service impl.

How do I call this main method from within gradle?  For now I'll make it be a unit test.


The FeeContext is going to be a challenge. Some calculators need the fee context, not just for the
number of sigs but also for the readable store so they can get, for example, the topic a message
is being submitted to so it can determine if there are custom fees applied.


 */

@ExtendWith(MockitoExtension.class)
public class SimpleFeesMirrorNodeTest {

    static class SimpleFeesCalculatorProvider {
        SimpleFeeCalculator make(FeeSchedule feeSchedule) {
            Set<ServiceFeeCalculator> serviceFeeCalculators = new HashSet<>();
            Set<QueryFeeCalculator> queryFeeCalculators = new HashSet<>();

            serviceFeeCalculators.addAll(ConsensusServiceImpl.getServiceFeeCalculators());
            serviceFeeCalculators.addAll(FileServiceImpl.getServiceFeeCalculators());
            serviceFeeCalculators.addAll(TokenServiceImpl.getServiceFeeCalculators());
            serviceFeeCalculators.addAll(ScheduleServiceImpl.getServiceFeeCalculators());
//            serviceFeeCalculators.addAll(CryptoServiceImpl.getServiceFeeCalculators());
//            serviceFeeCalculators.addAll(NetworkServiceImpl.getServiceFeeCalculators());
//            serviceFeeCalculators.addAll(UtilServiceImpl.getServiceFeeCalculators());

            queryFeeCalculators.addAll(ConsensusServiceImpl.getQueryFeeCalculators());

            return new SimpleFeeCalculatorImpl(feeSchedule, serviceFeeCalculators, queryFeeCalculators);
        }
    }

    @Test
    public void doTest() throws IOException, ParseException {
        System.out.println("calculating a fee for a create topic transaction");

        // load up the fee schedule from JSON file
        var input = SimpleFeesMirrorNodeTest.class.getClassLoader().getResourceAsStream("test-schedule.json");
        var bytes = input.readAllBytes();
        final FeeSchedule feeSchedule = FeeSchedule.JSON.parse(Bytes.wrap(bytes));

        // check the schedule is valid
        if (!isValid(feeSchedule)) {
            throw new Error("invalid fee schedule");
        }


        // create a simple fee calculator
        SimpleFeeCalculator calc = new SimpleFeesCalculatorProvider().make(feeSchedule);

        // build an example transaction
//        final var op = ConsensusCreateTopicTransactionBody.newBuilder().build();
//        final var txnBody = TransactionBody.newBuilder().consensusCreateTopic(op).build();
//        final var txn = Transaction.newBuilder().body(txnBody).build();
//        final var numSigs =  txn.sigMap().sigPair().size();
        final long topicEntityNum = 1L;
        final TopicID topicId =
                TopicID.newBuilder().topicNum(topicEntityNum).build();

        final var op = ConsensusSubmitMessageTransactionBody.newBuilder()
                .topicID(topicId)
                .message(Bytes.wrap("foo"))
                .build();
        final var txnBody =
                TransactionBody.newBuilder().consensusSubmitMessage(op).build();

        // create a fee context
        FeeContext feeContext = null;//new FeeContextImpl()

        FeeResult result = calc.calculateTxFee(txnBody, feeContext);
        System.out.println("the fees are " + result);
    }
}
