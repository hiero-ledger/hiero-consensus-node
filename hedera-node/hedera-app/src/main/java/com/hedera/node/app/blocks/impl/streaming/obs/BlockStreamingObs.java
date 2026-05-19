package com.hedera.node.app.blocks.impl.streaming.obs;

public interface BlockStreamingObs {

    void onBlockInit(final long blockNumber, final long nanosTick);

    void onBlockOpen(final long blockNumber, final long nanosTick);

    void onBlockItemAdd(final long blockNumber, final int itemIndex, final long nanosTick, final int sizeInBytes);

    void onBlockItemSend(final long blockNumber, final int itemIndexStart, final int itemIndexEnd, final long startNanosTick, final long endNanosTick);

    void onBlockEndSend(final long blockNumber, final long startNanosTick, final long endNanosTick);

    void onBlockClose(final long blockNumber, final long nanosTick);

    void onBlockAcked(final long blockNumber, final long nanosTick);

    void onBlockProofCreate(final long blockNumber, final long nanosTick);

    void onBlockProofAdd(final long blockNumber, final long nanosTick);

    void onBlockHeaderSend(final long blockNumber, final long startNanosTick, final long endNanosTick);

    void onBlockFooterCreate(final long blockNumber, final long nanosTick);


}
