package com.hedera.node.app.hints.impl;

/**
 * TODO - sealed interface first permitting just HintsContext.Signing and exposing
 * public methods exactly as used by any current client of HintsContext.Signing
 * <b>other than</b> {@link com.hedera.node.app.tss.TssBlockHashSigner}
 *
 * Then eliminate direct use of HintsContext.Signing everywhere except TssBlockHashSigner,
 * replacing those usage sites with BlockHashSigning
 */
public interface BlockHashSigning {
}
