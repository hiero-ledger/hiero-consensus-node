// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.internal;

import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.SwirldsPlatform;
import com.swirlds.platform.gui.components.ScrollableJPanel;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;

/**
 * Manages static variables for the browser GUI window.
 */
public final class BrowserWindowManager {

    private static Metrics metrics;

    private BrowserWindowManager() {}

    /**
     * the primary window used by Browser
     */
    private static WinBrowser browserWindow = null;

    /**
     * metadata about all known apps, swirlds, members, signed states
     */
    private static StateHierarchy stateHierarchy = null;

    /**
     * Platforms running in this JVM.
     */
    private static final Collection<SwirldsPlatform> platforms = new ArrayList<>();

    /**
     * Get the primary window used by Browser.
     */
    public static WinBrowser getBrowserWindow() {
        return browserWindow;
    }

    /**
     * Set the primary window used by Browser.
     */
    public static void setBrowserWindow(final WinBrowser browserWindow) {
        BrowserWindowManager.browserWindow = browserWindow;
    }

    /**
     * Get metadata about all known apps, swirlds, members, signed states.
     */
    public static StateHierarchy getStateHierarchy() {
        return stateHierarchy;
    }

    /**
     * Set metadata about all known apps, swirlds, members, signed states.
     */
    public static void setStateHierarchy(final StateHierarchy stateHierarchy) {
        BrowserWindowManager.stateHierarchy = stateHierarchy;
    }

    /**
     * Get the platforms that are running on this machine.
     */
    public static Collection<SwirldsPlatform> getPlatforms() {
        return platforms;
    }

    /**
     * Add a collection of platforms to the list of platforms running on this machine.
     *
     * @param toAdd the platforms to add
     */
    public static void addPlatforms(@NonNull final Collection<SwirldsPlatform> toAdd) {
        Objects.requireNonNull(toAdd);

        synchronized (platforms) {
            platforms.addAll(toAdd);
        }
    }

    /**
     * Make the browser window visible. If it doesn't yet exist, then create it. Then switch to the given tab, with a
     * component name of the form Browser.browserWindow.tab* such as Browser.browserWindow.tabCalls to switch to the
     * "Calls" tab.
     *
     * @param comp the index of the tab to select
     */
    public static void showBrowserWindow(@Nullable final ScrollableJPanel comp) {

        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        if (getBrowserWindow() != null) {
            getBrowserWindow().setVisible(true);
            return;
        }
        getBrowserWindow().goTab(comp);
    }

    /**
     * Move the browser window to the front of the screen.
     */
    public static void moveBrowserWindowToFront() {
        for (final Frame frame : Frame.getFrames()) {
            if (!frame.equals(getBrowserWindow())) {
                frame.toFront();
            }
        }
    }
}
