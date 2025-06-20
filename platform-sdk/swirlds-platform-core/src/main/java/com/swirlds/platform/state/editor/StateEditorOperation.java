// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.editor;

import com.swirlds.cli.utility.ParameterizedClass;

/**
 * A state editor command.
 */
public abstract class StateEditorOperation extends ParameterizedClass implements Runnable {

    private StateEditor stateEditor;

    /**
     * Set the state editor instance.
     */
    public void setStateEditor(final StateEditor stateEditor) {
        this.stateEditor = stateEditor;
    }

    /**
     * Get the state editor instance.
     */
    public StateEditor getStateEditor() {
        if (stateEditor == null) {
            throw new IllegalStateException("State editor not set");
        }
        return stateEditor;
    }
}
