// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui;

import static com.swirlds.platform.gui.GuiUtils.winRect;

import com.swirlds.common.Console;
import com.swirlds.platform.gui.internal.SwirldMenu;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.system.Platform;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

/**
 * Utilities for creating GUI elements.
 */
public final class SwirldsGui {

    private static final Logger logger = LogManager.getLogger(SwirldsGui.class);

    private SwirldsGui() {}

    /**
     * Create a new window with a text console, of the recommended size and location, including the Swirlds menu.
     *
     * @param platform the platform to create the console with
     * @param winNum the number of the window, starting at 0
     * @param visible  should the window be initially visible? If not, call setVisible(true) later.
     * @return the new window
     */
    public static Console createConsole(@NonNull final Platform platform, final int winNum, final boolean visible) {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        final NodeId selfId = platform.getSelfId();
        final int winCount = platform.getRoster().rosterEntries().size();
        final Rectangle winRect = winRect(winCount, winNum);
        // if SwirldMain calls createConsole, this remembers the window created
        final Console console = GuiUtils.createBasicConsole(RosterUtils.formatNodeName(selfId.id()), winRect, visible);
        SwirldMenu.addTo(platform, console.getWindow(), 40, Color.white, false);
        return console;
    }
}
