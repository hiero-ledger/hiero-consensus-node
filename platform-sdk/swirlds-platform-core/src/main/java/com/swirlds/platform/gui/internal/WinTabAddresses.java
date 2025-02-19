/*
 * Copyright (C) 2016-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.gui.internal;

import static com.swirlds.platform.gui.GuiUtils.wrap;
import static com.swirlds.platform.gui.internal.BrowserWindowManager.getPlatforms;

import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.platform.SwirldsPlatform;
import com.swirlds.platform.gui.GuiUtils;
import com.swirlds.platform.gui.components.PrePaintableJPanel;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.system.Platform;
import java.util.Collection;
import javax.swing.JTextArea;

/**
 * The tab in the Browser window that shows available apps, running swirlds, and saved swirlds.
 */
class WinTabAddresses extends PrePaintableJPanel {
    private static final long serialVersionUID = 1L;
    /** the entire table is in this single Component */
    private JTextArea text;
    /** should the entire window be rebuilt? */
    private boolean redoWindow = true;

    /**
     * Instantiate and initialize content of this tab.
     */
    public WinTabAddresses() {
        text = GuiUtils.newJTextArea("");
        add(text);
    }

    /** {@inheritDoc} */
    public void prePaint() {
        if (!redoWindow) {
            return;
        }
        redoWindow = false;
        String s = "";
        final Collection<SwirldsPlatform> platforms = getPlatforms();

        synchronized (platforms) {
            for (final Platform p : platforms) {
                final RosterEntry entry =
                        RosterUtils.getRosterEntry(p.getRoster(), p.getSelfId().id());
                final String name = RosterUtils.formatNodeName(entry.nodeId());
                s += "\n" + entry.nodeId() + "   " + name
                        + "   " + name
                        + "   " + RosterUtils.fetchHostname(entry, 0)
                        + "   " + RosterUtils.fetchPort(entry, 0);
            }
        }
        s += wrap(
                70,
                "\n\n"
                        + "The above are all the member addresses. "
                        + "Each address includes the nickname, name, "
                        + "internal hostname/port and external hostname/port.");

        text.setText(s);
    }
}
