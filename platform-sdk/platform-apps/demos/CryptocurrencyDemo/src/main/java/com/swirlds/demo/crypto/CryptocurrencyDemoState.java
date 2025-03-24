// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.crypto;
/*
 * This file is public domain.
 *
 * SWIRLDS MAKES NO REPRESENTATIONS OR WARRANTIES ABOUT THE SUITABILITY OF
 * THE SOFTWARE, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE, OR NON-INFRINGEMENT. SWIRLDS SHALL NOT BE LIABLE FOR
 * ANY DAMAGES SUFFERED AS A RESULT OF USING, MODIFYING OR
 * DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES.
 */

import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.common.constructable.ConstructableIgnored;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.merkle.MerkleStateRoot;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.hiero.consensus.model.node.NodeId;

/**
 * This holds the current state of a swirld representing both a cryptocurrency and a stock market.
 *
 * This is just a simulated stock market, with fictitious stocks and ticker symbols. But the cryptocurrency
 * is actually real. At least, it is real in the sense that if enough people participate for long enough
 * (and if Swirlds has encryption turned on), then it could actually be a reliable cryptocurrency. An
 * entirely new cryptocurrency is created every time all the computers start the program over again, so
 * these cryptocurrencies won't have any actual value.
 */
@ConstructableIgnored
public class CryptocurrencyDemoState extends MerkleStateRoot<CryptocurrencyDemoState> implements MerkleNodeState {

    /**
     * The version history of this class.
     * Versions that have been released must NEVER be given a different value.
     */
    private static class ClassVersion {
        /**
         * In this version, serialization was performed by copyTo/copyToExtra and deserialization was performed by
         * copyFrom/copyFromExtra. This version is not supported by later deserialization methods and must be handled
         * specially by the platform.
         */
        public static final int ORIGINAL = 1;
        /**
         * In this version, serialization was performed by serialize/deserialize.
         */
        public static final int MIGRATE_TO_SERIALIZABLE = 2;
    }

    private static final long CLASS_ID = 0x66b6e816ef864279L;

    private static final int DEFAULT_MAX_ARRAY_SIZE = 1024 * 8;
    private static final int DEFAULT_MAX_STRING_SIZE = 128;

    /**
     * the first byte of a transaction is the ordinal of one of these four: do not delete any of these or
     * change the order (and add new ones only to the end)
     */
    public enum TransType {
        slow,
        fast,
        bid,
        ask // run slow/fast or broadcast a bid/ask
    }

    /** number of different stocks that can be bought and sold */
    public static final int NUM_STOCKS = 10;
    /** remember the last MAX_TRADES trades that occurred. */
    private static final int MAX_TRADES = 200;

    ////////////////////////////////////////////////////
    // the following are the shared state:

    /** ticker symbols for each of the stocks */
    private String[] tickerSymbol;
    /** number of cents owned by each member */
    private Map<NodeId, AtomicLong> wallet;
    /** shares[m][s] is the number of shares that member m owns of stock s */
    private Map<NodeId, List<AtomicLong>> shares;
    /** a record of the last NUM_TRADES trades */
    private String[] trades;
    /** number of trades currently stored in trades[] (from 0 to MAX_TRADES, inclusive) */
    private int numTradesStored = 0;
    /** the latest trade was stored in trades[lastTradeIndex] */
    private int lastTradeIndex = 0;
    /** how many trades have happened in all history */
    private long numTrades = 0;
    /** the most recent price (in cents) that a seller has offered for each stock */
    private byte[] ask;
    /** the most recent price (in cents) that a buyer has offered for each stock */
    private byte[] bid;
    /** the ID number of the member whose offer is stored in ask[] (or -1 if none) */
    private NodeId[] askId;
    /** the ID number of the member whose offer is stored in bid[] (or -1 if none) */
    private NodeId[] bidId;
    /** price of the most recent trade for each stock */
    private byte[] price;

    ////////////////////////////////////////////////////

    public CryptocurrencyDemoState() {
        // no-op
    }

    private CryptocurrencyDemoState(final CryptocurrencyDemoState sourceState) {
        super(sourceState);
        this.tickerSymbol = sourceState.tickerSymbol.clone();
        this.wallet = new HashMap<>(sourceState.wallet);
        this.shares = new HashMap<>();
        sourceState.shares.forEach((nodeId, sharesForNode) -> this.shares.put(nodeId, new ArrayList<>(sharesForNode)));
        this.trades = sourceState.trades.clone();
        this.numTradesStored = sourceState.numTradesStored;
        this.lastTradeIndex = sourceState.lastTradeIndex;
        this.numTrades = sourceState.numTrades;
        this.ask = sourceState.ask.clone();
        this.bid = sourceState.bid.clone();
        this.askId = sourceState.askId.clone();
        this.bidId = sourceState.bidId.clone();
        this.price = sourceState.price.clone();
    }

    /**
     * get the string representing the trade with the given sequence number. The first trade in all of
     * history is sequence 1, the next is 2, etc.
     *
     * @param seq
     * 		the sequence number of the trade
     * @return the trade, or "" if it hasn't happened yet or happened so long ago that it is no longer
     * 		stored
     */
    public synchronized String getTrade(final long seq) {
        if (seq > numTrades || seq <= numTrades - numTradesStored) {
            return "";
        }
        return trades[(int) ((lastTradeIndex + seq - numTrades + MAX_TRADES) % MAX_TRADES)];
    }

    /**
     * get the current price of each stock, copying it into the given array
     *
     * @param price
     * 		the array of NUM_STOCKS elements that will be filled with the prices
     */
    public synchronized void getPriceCopy(final byte[] price) {
        for (int i = 0; i < NUM_STOCKS; i++) {
            price[i] = this.price[i];
        }
    }

    /**
     * return how many trades have occurred. So getTrade(getNumTrades()) will return a non-empty string (if
     * any trades have ever occurred), but getTrade(getNumTrades()+1) will return "" (unless one happens
     * between the two method calls).
     *
     * @return number of trades
     */
    public synchronized long getNumTrades() {
        return numTrades;
    }

    @NonNull
    @Override
    public synchronized CryptocurrencyDemoState copy() {
        throwIfImmutable();
        setImmutable(true);
        return new CryptocurrencyDemoState(this);
    }

    void handleTransaction(NodeId id, int askBid, int tradeStock, int tradePrice) {
        if (askBid == CryptocurrencyDemoState.TransType.ask.ordinal()) { // it is an ask
            // if they're trying to sell something they don't have, then ignore it
            if (shares.get(id).get(tradeStock).get() == 0) {
                return;
            }
            // if previous member with bid no longer has enough money, then forget them
            if (bidId[tradeStock] != null && wallet.get(bidId[tradeStock]).get() < bid[tradeStock]) {
                bidId[tradeStock] = null;
            }
            // if this is the lowest ask for this stock since its last trade, then remember it
            if (askId[tradeStock] == null || tradePrice < ask[tradeStock]) {
                askId[tradeStock] = id;
                ask[tradeStock] = (byte) tradePrice;
            }
        } else { // it is a bid
            // if they're trying to buy but don't have enough money, then ignore it
            if (shares.get(id).get(tradeStock).get() == 0) {
                return;
            }
            // if previous member with ask no longer has the share, then forget them
            if (askId[tradeStock] != null
                    && shares.get(askId[tradeStock]).get(tradeStock).get() == 0) {
                askId[tradeStock] = null;
            }
            // if this is the highest bid for this stock since its last trade, then remember it
            if (bidId[tradeStock] == null || tradePrice > bid[tradeStock]) {
                bidId[tradeStock] = id;
                bid[tradeStock] = (byte) tradePrice;
            }
        }
        // if there is not yet a match for this stock, then don't create a trade yet
        if (askId[tradeStock] == null || bidId[tradeStock] == null || ask[tradeStock] > bid[tradeStock]) {
            return;
        }

        // there is a match, so create the trade

        // the trade occurs at the mean of the ask and bid
        // if the mean is a non-integer, round to the nearest event integer
        tradePrice = ask[tradeStock] + bid[tradeStock];
        tradePrice = (tradePrice / 2) + ((tradePrice % 4) / 3);

        // perform the trade (exchanging money for a share)
        wallet.get(askId[tradeStock]).addAndGet(tradePrice); // seller gets money
        wallet.get(bidId[tradeStock]).addAndGet(-tradePrice); // buyer gives money
        shares.get(askId[tradeStock]).get(tradeStock).addAndGet(-1); // seller gives 1 share
        shares.get(bidId[tradeStock]).get(tradeStock).addAndGet(1); // buyer gets 1 share

        // save a description of the trade to show on the console
        final String selfName = RosterUtils.formatNodeName(id.id());
        final String sellerNickname = RosterUtils.formatNodeName(askId[tradeStock].id());
        final String buyerNickname = RosterUtils.formatNodeName(bidId[tradeStock].id());
        final int change = tradePrice - price[tradeStock];
        final double changePerc = 100. * change / price[tradeStock];
        final String dir = (change > 0) ? "^" : (change < 0) ? "v" : " ";
        numTrades++;
        numTradesStored = Math.min(MAX_TRADES, 1 + numTradesStored);
        lastTradeIndex = (lastTradeIndex + 1) % MAX_TRADES;
        final String tradeDescription = String.format(
                "%6d %6s %7.2f %s %4.2f %7.2f%% %7s->%s      %5s has $%-8.2f and shares: %s",
                numTrades,
                tickerSymbol[tradeStock],
                tradePrice / 100.,
                dir,
                Math.abs(change / 100.),
                Math.abs(changePerc),
                sellerNickname,
                buyerNickname,
                selfName,
                wallet.get(id).get() / 100.,
                shares.get(id));

        // record the trade, and say there are now no pending asks or bids
        trades[lastTradeIndex] = tradeDescription;
        price[tradeStock] = (byte) tradePrice;
        askId[tradeStock] = null;
        bidId[tradeStock] = null;
    }

    /**
     * Do setup at genesis
     */
    void genesisInit(@NonNull final Platform platform) {
        tickerSymbol = new String[NUM_STOCKS];
        wallet = new HashMap<>();
        shares = new HashMap<>();
        trades = new String[MAX_TRADES];
        numTradesStored = 0;
        lastTradeIndex = 0;
        numTrades = 0;
        ask = new byte[NUM_STOCKS];
        bid = new byte[NUM_STOCKS];
        askId = new NodeId[NUM_STOCKS];
        bidId = new NodeId[NUM_STOCKS];
        price = new byte[NUM_STOCKS];

        // seed 0 so everyone gets same ticker symbols on every run
        final Random rand = new Random(0);
        for (int i = 0; i < NUM_STOCKS; i++) {
            tickerSymbol[i] = "" // each ticker symbol is 4 random capital letters (ASCII 65 is 'A')
                    + (char) (65 + rand.nextInt(26))
                    + (char) (65 + rand.nextInt(26))
                    + (char) (65 + rand.nextInt(26))
                    + (char) (65 + rand.nextInt(26));
            askId[i] = bidId[i] = null; // no one has offered to buy or sell yet
            ask[i] = bid[i] = price[i] = 64; // start the trading around 64 cents
        }

        for (final RosterEntry address : platform.getRoster().rosterEntries()) {
            final NodeId id = NodeId.of(address.nodeId());
            // each member starts with $200 dollars (20,000 cents)
            wallet.put(id, new AtomicLong(20000L));
            final List<AtomicLong> sharesForID = new ArrayList<>();
            for (int i = 0; i < NUM_STOCKS; i++) {
                // each member starts with 200 shares of each stock
                sharesForID.add(new AtomicLong(200L));
            }
            shares.put(id, sharesForID);
        }
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return ClassVersion.MIGRATE_TO_SERIALIZABLE;
    }

    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.MIGRATE_TO_SERIALIZABLE;
    }

    @Override
    protected CryptocurrencyDemoState copyingConstructor() {
        return new CryptocurrencyDemoState(this);
    }
}
