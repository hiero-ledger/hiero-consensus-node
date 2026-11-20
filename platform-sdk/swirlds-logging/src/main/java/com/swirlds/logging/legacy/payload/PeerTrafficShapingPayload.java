// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

/**
 * This payload is logged when a peer's inbound byte budget crosses a reporting watermark.
 *
 * <p>Emitted by the gossip per-peer traffic shaper. Tests match on the payload type rather than on message text,
 * so the human readable message can change freely without breaking them.
 */
public class PeerTrafficShapingPayload extends AbstractLogPayload {

    private long peerId;
    private long occupancyPerMille;
    private long thresholdPerMille;
    private long delayNanos;
    private boolean enforced;

    public PeerTrafficShapingPayload() {}

    /**
     * @param message           a human readable message, must not contain '{'
     * @param peerId            the peer whose budget was breached
     * @param occupancyPerMille the fraction of the burst budget consumed, in per-mille
     * @param thresholdPerMille the watermark that was breached, in per-mille
     * @param delayNanos        the pause applied to the read thread, or 0 if none was applied
     * @param enforced          {@code true} if the pause was actually applied, {@code false} in shadow mode
     */
    public PeerTrafficShapingPayload(
            final String message,
            final long peerId,
            final long occupancyPerMille,
            final long thresholdPerMille,
            final long delayNanos,
            final boolean enforced) {
        super(message);
        this.peerId = peerId;
        this.occupancyPerMille = occupancyPerMille;
        this.thresholdPerMille = thresholdPerMille;
        this.delayNanos = delayNanos;
        this.enforced = enforced;
    }

    public long getPeerId() {
        return peerId;
    }

    public void setPeerId(final long peerId) {
        this.peerId = peerId;
    }

    public long getOccupancyPerMille() {
        return occupancyPerMille;
    }

    public void setOccupancyPerMille(final long occupancyPerMille) {
        this.occupancyPerMille = occupancyPerMille;
    }

    public long getThresholdPerMille() {
        return thresholdPerMille;
    }

    public void setThresholdPerMille(final long thresholdPerMille) {
        this.thresholdPerMille = thresholdPerMille;
    }

    public long getDelayNanos() {
        return delayNanos;
    }

    public void setDelayNanos(final long delayNanos) {
        this.delayNanos = delayNanos;
    }

    public boolean isEnforced() {
        return enforced;
    }

    public void setEnforced(final boolean enforced) {
        this.enforced = enforced;
    }
}
