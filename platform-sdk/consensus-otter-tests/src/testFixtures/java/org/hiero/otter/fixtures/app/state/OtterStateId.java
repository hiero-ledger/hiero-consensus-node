package org.hiero.otter.fixtures.app.state;


public enum OtterStateId {

    CONSISTENCY_SINGLETON_STATE_ID(0);

    private final int id;

    OtterStateId(final int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }
}
