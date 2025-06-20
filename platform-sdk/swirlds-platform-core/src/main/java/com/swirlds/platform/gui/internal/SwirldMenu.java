// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.internal;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.platform.system.SystemExitUtils.exitSystem;

import com.swirlds.platform.gui.components.ScrollableJPanel;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.SystemExitCode;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Desktop;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.WindowConstants;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A Swirlds menu icon that goes in the upper-right corner of a window, to allow the user to access the browser and
 * other platform functions.
 */
public class SwirldMenu extends JPanel {
    // the menu's window's app's Platform
    private Platform platform;
    // this fraction of logo's height is margin on each side
    private static float margin = 0.1f;
    // for serializing
    private static final long serialVersionUID = 1L;
    // foreground color of logo
    private Paint foreColor = null;
    // background color, or null for transparent
    private Paint backColor = null;
    // location of upper-left corner
    private int x = 0;
    private int y = 0;
    // the window whose root layered pane will be my parent
    // (parent is passed in and saved, and may be used someday)
    @SuppressWarnings("unused")
    private JFrame parent = null;
    // number of pixels high and wide
    private int size = 10;
    // standard blue for logo
    private static Color logoBlue = new Color(0, 76, 151, 255);
    /** use this for all logging, as controlled by the optional data/log4j2.xml file */
    private static final Logger logger = LogManager.getLogger(SwirldMenu.class);

    /**
     * Add a Swirlds menu icon to the upper-right corner of the given window. The height and width of it will be size
     * pixels. The logo will be the standard blue-on-white colors. If the given window already has a Swirlds menu icon,
     * then that old menu will be removed.
     *
     * @param platform the Platform running the app that owns this window
     * @param window   the window to which the icon will be added
     * @param size     the height and width of the icon, in pixels
     */
    public static void addTo(Platform platform, JFrame window, int size) {
        addTo(platform, window, size, Color.BLUE, true);
    }

    /**
     * Add a Swirlds menu icon to the upper-right corner of the given window. The height and width of it will be size
     * pixels. If the given window already has a Swirlds menu icon, then that old menu will be removed. The logo will be
     * blue, black, or white, according to foreColor. If there is a background, it will be white, white, or black
     * (respectively). If backColor is false, then the background is transparent.
     * <p>
     * NOTE: SwirldsMenu is a lightweight Component. So the transparent background will work if the window contains only
     * lightweight Components, such as JPanel. If the window contains a heavyweight Component, such as a Canvas, then it
     * is better to set backColor to true.
     *
     * @param platform  the Platform running the app that owns this window
     * @param window    the window to add this menu to
     * @param size      height and width, in pixels
     * @param foreColor must be one of Color.BLUE, Color.WHITE, Color.BLACK
     * @param backColor should background square be non-transparent?
     */
    public static void addTo(Platform platform, JFrame window, int size, Color foreColor, boolean backColor) {
        Color fore = null;
        Color back = null;

        if (foreColor == Color.WHITE) {
            fore = Color.WHITE;
            back = Color.BLACK;
        } else if (foreColor == Color.BLACK) {
            fore = Color.BLACK;
            back = Color.WHITE;
        } else {
            fore = logoBlue;
            back = Color.WHITE;
        }

        SwirldMenu logo = new SwirldMenu(platform, window, size, fore, backColor ? back : null);
        removeFrom(window); // get rid of the old one, if any exists
        JLayeredPane layeredPane = window.getRootPane().getLayeredPane();
        layeredPane.add(logo, JLayeredPane.MODAL_LAYER);
        layeredPane.moveToFront(logo);
        logo.setVisible(true);
    }

    /**
     * Delete the Swirlds menu icon from the given window. If there is no such menu, then nothing happens.
     *
     * @param window the window to remove it from
     */
    public static void removeFrom(JFrame window) {
        removeFromRecursive(null, window.getRootPane());
        window.revalidate();
    }

    /**
     * Recursively search through all components in the container tree, and delete any SwirldsMenu objects found. The
     * search starts at root. The root should be immediately inside the given parent Container.
     *
     * @param root the root of the tree to search
     */
    private static void removeFromRecursive(Container parent, Component root) {
        if (root instanceof SwirldMenu) {
            parent.remove(root);
        } else if (root instanceof Container) {
            Container cont = (Container) root;
            for (Component c : cont.getComponents()) {
                removeFromRecursive(cont, c); // recursively look at every component in the tree
            }
        }
    }

    /**
     * Constructor for a menu in the upper-right of the window with the Swirlds logo. The parent is the window to add
     * this to. It should already be set to the right size.
     *
     * @param platform  the Platform running the app that owns this window
     * @param parent    the window to add the menu to
     * @param size      the height and width of the logo icon, in pixels
     * @param foreColor the color of the icon
     * @param backColor the background color of the icon, or transparent if null
     */
    SwirldMenu(Platform platform, JFrame parent, int size, Paint foreColor, Paint backColor) {
        this.platform = platform;
        this.x = 0;
        this.y = 0;
        this.parent = parent;
        this.size = size;
        this.foreColor = foreColor;
        this.backColor = backColor;
        int x = parent.getWidth() - size - getInsets().left - getInsets().right - size / 16;
        int y = size / 16;
        this.setBounds(x, y, size, size);
        this.setOpaque(false); // make it transparent
        this.setBackground(new Color(0, 0, 0, 0)); // make it transparent

        parent.addComponentListener(
                new ComponentListener() { // resize when parent does
                    @Override
                    public void componentResized(ComponentEvent e) {
                        int x = parent.getWidth() - size - getInsets().left - getInsets().right - size / 16;
                        int y = size / 16;
                        setBounds(x, y, size, size);
                    }

                    @Override
                    public void componentMoved(ComponentEvent e) {}

                    @Override
                    public void componentShown(ComponentEvent e) {}

                    @Override
                    public void componentHidden(ComponentEvent e) {}
                });

        MenuActionListener mal = new MenuActionListener();
        JPopupMenu popup = new JPopupMenu();
        for (int i = 0; i < mal.menuNames.length; i++) {
            if ("".equals(mal.menuNames[i])) {
                popup.addSeparator();
            } else {
                JMenuItem item = new JMenuItem(mal.menuNames[i]);
                item.addActionListener(mal);
                popup.add(item);
            }
        }

        popup.pack();

        this.addMouseListener(new Listener(popup));
    }

    /**
     * Make the popup pop up when clicked on
     */
    private class Listener extends MouseAdapter {
        JPopupMenu popup = null;
        int width = 0;

        Listener(JPopupMenu popup) {
            this.popup = popup;
            width = popup.getPreferredSize().width;
        }

        // don't check e.isPopupTrigger because this should act like a menu, not like a popup
        public void mousePressed(MouseEvent e) {
            doPop(e);
        }

        public void mouseReleased(MouseEvent e) {
            doPop(e);
        }

        private void doPop(MouseEvent e) {
            popup.show(e.getComponent(), size - width, size);
        }
    }

    class MenuActionListener implements ActionListener {
        // each menu item should appear both in the string array and the switch
        final String[] menuNames = new String[] {
            // "Help",
            "About",
            "-",
            "Browser",
            // "Call",
            // "Post",
            "-",
            "Quit"
        };

        @Override
        public void actionPerformed(ActionEvent e) {
            switch (e.getActionCommand()) {
                case "Help":
                    break;
                case "About":
                    int choice = JOptionPane.showOptionDialog(
                            null, // parentCompoinent
                            (platform == null ? "" : "placeholder"),
                            "About this app", // title
                            JOptionPane.DEFAULT_OPTION, // optionType
                            JOptionPane.PLAIN_MESSAGE /* INFORMATION_MESSAGE */, // messageType
                            null, // icon
                            new String[] {"OK", "Acknowledgments", "License"}, // options
                            "OK"); // initialValue
                    if (choice == 1) {
                        // location Eclipse looks: platform/target/classes/docs/acknowledgments.html
                        // location command line looks: swirlds.jar/docs/acknowledgments.html
                        popupHtml("/docs/acknowledgments.html");
                    } else if (choice == 2) {
                        // location Eclipse looks: platform/target/classes/docs/acknowledgments.html
                        // location command line looks: swirlds.jar/docs/acknowledgments.html
                        popupHtml("/docs/license.html");
                    }
                    break;
                case "Browser":
                    showBrowserWindow(null);
                    break;
                case "Call":
                    showBrowserWindow(WinBrowser.tabCalls);
                    break;
                case "Post":
                    showBrowserWindow(WinBrowser.tabPosts);
                    break;
                case "Quit":
                    exitSystem(SystemExitCode.NO_ERROR, "quit", true);
                    break;
                case "Default":
                    break;
            }
        }
    }

    private void showBrowserWindow(@Nullable final ScrollableJPanel comp) {
        BrowserWindowManager.showBrowserWindow(null);
    }

    /**
     * Popup a dialog box with an OK button, giving a scrollable view of the given HTML file within the .jar file.
     * <p>
     * For example, if the path is "/docs/license.html", then the license.html file must be in the docs directory inside
     * the swirlds.jar file.
     * <p>
     * That will work from the command line. To also work in Eclipse, the file must also be copied to
     * platform/target/classes/docs/license.html
     *
     * @param path the path such as "/docs/license.html" for the file license.html located in both locations.
     */
    void popupHtml(String path) {
        JEditorPane display = new JEditorPane("text/html", "");
        URL url = getClass().getResource(path);
        try {
            display.setPage(url);
        } catch (IOException e) {
            logger.error(EXCEPTION.getMarker(), "", e);
        }
        display.setEditable(false);
        display.addHyperlinkListener(new HyperlinkListener() {
            public void hyperlinkUpdate(HyperlinkEvent e) {
                if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                    if (Desktop.isDesktopSupported()) {
                        try {
                            Desktop.getDesktop().browse(e.getURL().toURI());
                        } catch (IOException | URISyntaxException e1) {
                        }
                    }
                }
            }
        });
        JScrollPane scroll = new JScrollPane(display);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        JPanel middlePanel = new JPanel(new BorderLayout());
        middlePanel.add(scroll, BorderLayout.CENTER);
        JFrame frame = new JFrame();
        frame.add(middlePanel);
        frame.pack();
        frame.setSize(800, 500);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setVisible(true);
    }

    /**
     * Called by the JVM to update the menu icon. It just updates its parent, then draws the logo.
     */
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        drawLogo((Graphics2D) g, x, y, size, foreColor, backColor);
    }

    /**
     * Draw the Swirlds logo into the given Graphics2D context, with upper-left corner at pixel (x,y), with width and
     * height both equal to scale pixels, wtih the given foreground color. If the background color is not null, then the
     * background is filled with the given color.
     *
     * @param g         Graphics2D context.
     * @param x         x coordinate of location of icon
     * @param y         y coordinate of location of icon
     * @param size      draw logo at size by size pixels
     * @param foreColor draw logo with this color
     * @param backColor fill background with this color (null means don't fill background)
     */
    static void drawLogo(Graphics2D g, float x, float y, float size, Paint foreColor, Paint backColor) {

        float xx = 0, yy = 0, w = 300, h = 300; // SVG had upper-left corner (xx,yy), size (w,h)
        float scale = size / w; // 1.0 means 300x300, as encoded in the SVG

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.translate(x, y);
        g.scale(scale, scale);

        if (backColor != null) { // fill the background, if a non-null color was given
            g.setPaint(backColor);
            Shape shape = new Rectangle2D.Double(xx, yy, w, h);
            g.fill(shape);
        }

        g.translate(margin * w, margin * w);
        g.scale(1 - 2 * margin, 1 - 2 * margin);

        GeneralPath path = new GeneralPath();
        setLogoShape(path);
        g.setPaint(foreColor);
        g.fill(path);
    }

    /**
     * Create the shape, as defined by the original SVG, which was translated into this Java code by
     * <a href="http://englishjavadrinker.blogspot.com/search/label/SVGRoundTrip">SVGRoundTrip</a> The
     * original bounding box in the SVG was at (x,y)=(1,1) and size (300,300)
     *
     * @param path
     */
    private static void setLogoShape(GeneralPath path) {
        path.moveTo(238.40625, 1.0);
        path.curveTo(215.9813, 1.0, 196.42497, 13.18268, 185.9375, 31.28125);
        path.curveTo(185.9345, 31.28575, 185.9089, 31.27675, 185.9063, 31.28125);
        path.curveTo(155.41014, 31.28125, 128.77777, 47.77176, 114.406296, 72.3125);
        path.curveTo(125.195465, 72.56807, 137.90039, 83.5734, 149.0938, 101.84375);
        path.curveTo(154.87167, 111.27466, 160.27972, 122.697296, 164.93755, 135.25);
        path.curveTo(186.66602, 132.34451, 202.41959, 127.49816, 207.50005, 122.15625);
        path.curveTo(199.33109, 110.20831, 188.94466, 99.89669, 176.9063, 91.84375);
        path.curveTo(189.2805, 92.27962, 202.17589, 91.78279, 213.4063, 90.4375);
        path.curveTo(214.99872, 96.67013, 216.33743, 103.22096, 217.4063, 109.8125);
        path.curveTo(219.31734, 121.5975, 220.28459, 133.47102, 220.2188, 144.0625);
        path.curveTo(219.90749, 144.1365, 219.59497, 144.20938, 219.2813, 144.28125);
        path.curveTo(217.60811, 140.05661, 215.68007, 135.95663, 213.5313, 132.0);
        path.curveTo(208.50676, 138.71666, 192.21298, 144.85283, 169.4688, 148.59375);
        path.curveTo(172.02301, 156.79294, 174.26663, 165.32774, 176.0938, 173.9375);
        path.curveTo(179.85025, 191.63818, 181.85794, 209.62968, 182.0313, 225.96875);
        path.curveTo(205.93324, 218.04227, 222.4498, 205.78693, 226.9688, 192.625);
        path.curveTo(227.09953, 190.3979, 227.18755, 188.16618, 227.18755, 185.90625);
        path.lineTo(227.18755, 185.87505);
        path.curveTo(251.99524, 171.5786, 268.70752, 144.81454, 268.7188, 114.125046);
        path.curveTo(286.83777, 103.64282, 299.0313, 84.065285, 299.0313, 61.625046);
        path.curveTo(299.03125, 28.139975, 271.89127, 1.0, 238.40625, 1.0);
        path.closePath();
        path.moveTo(238.40625, 13.0);
        path.curveTo(265.26385, 13.0, 287.03125, 34.76739, 287.03125, 61.625);
        path.curveTo(287.03125, 77.515625, 279.41077, 91.62734, 267.625, 100.5);
        path.curveTo(261.86862, 65.67153, 234.35478, 38.17729, 199.53125, 32.40625);
        path.curveTo(208.40392, 20.620466, 222.51562, 13.0, 238.40625, 13.0);
        path.closePath();
        path.moveTo(185.90625, 43.34375);
        path.curveTo(191.44559, 43.40172, 197.98676, 50.467052, 203.78125, 62.6875);
        path.curveTo(206.73418, 68.915146, 209.45168, 76.46314, 211.8125, 84.6875);
        path.curveTo(197.51219, 86.17292, 180.49464, 86.35702, 165.59375, 85.1875);
        path.curveTo(165.58145, 85.1812, 165.57484, 85.16256, 165.56255, 85.15625);
        path.curveTo(163.88718, 84.298904, 162.19127, 83.46242, 160.4688, 82.6875);
        path.curveTo(162.71353, 75.17427, 165.25995, 68.282845, 168.00005, 62.53125);
        path.curveTo(173.7884, 50.381172, 180.38303, 43.37297, 185.9063, 43.34375);
        path.closePath();
        path.moveTo(188.84375, 43.34375);
        path.curveTo(212.36365, 44.3011, 232.90257, 56.73452, 245.0625, 75.1875);
        path.curveTo(241.97466, 78.83102, 231.49802, 82.14434, 217.0, 84.0625);
        path.curveTo(214.10294, 75.674255, 210.68855, 68.040504, 207.03125, 61.78125);
        path.curveTo(201.17294, 51.755096, 194.70738, 45.065342, 188.84375, 43.34375);
        path.closePath();
        path.moveTo(182.25, 43.375);
        path.curveTo(182.44589, 43.3651, 182.64745, 43.3833, 182.84375, 43.375);
        path.curveTo(177.00885, 45.13854, 170.53397, 51.739002, 164.71875, 61.65625);
        path.curveTo(161.52213, 67.10776, 158.54651, 73.636406, 155.90625, 80.78125);
        path.curveTo(147.0391, 77.251366, 137.61815, 74.81065, 127.8125, 73.625);
        path.curveTo(139.95732, 56.221565, 159.699, 44.51786, 182.25, 43.375);
        path.closePath();
        path.moveTo(113.3125, 72.8125);
        path.curveTo(105.10827, 73.90928, 95.56561, 85.32122, 87.125, 104.3125);
        path.curveTo(83.00065, 113.59224, 79.15219, 124.64536, 75.78125, 136.6875);
        path.curveTo(99.891304, 138.92805, 128.43066, 138.91641, 152.5, 136.6563);
        path.curveTo(149.11362, 124.56541, 145.23827, 113.457146, 141.09375, 104.156296);
        path.curveTo(132.4205, 84.69237, 122.53126, 73.23105, 114.1875, 72.75);
        path.curveTo(113.94398, 72.73596, 113.57216, 72.77779, 113.3125, 72.8125);
        path.closePath();
        path.moveTo(109.71875, 72.90625);
        path.curveTo(72.74487, 74.31313, 40.32231, 93.46686, 20.71875, 122.09375);
        path.curveTo(25.725374, 127.45784, 41.489822, 132.33069, 63.28125, 135.25);
        path.curveTo(67.94162, 122.6814, 73.3417, 111.21856, 79.125, 101.78125);
        path.curveTo(88.93477, 85.77346, 99.89529, 75.38734, 109.71875, 72.90625);
        path.closePath();
        path.moveTo(247.5625, 79.28125);
        path.curveTo(253.38281, 89.56544, 256.71875, 101.43179, 256.71875, 114.09375);
        path.curveTo(256.71875, 116.38958, 256.58853, 118.66641, 256.375, 120.90625);
        path.curveTo(253.27534, 129.4107, 242.38222, 137.27219, 226.84375, 142.21875);
        path.curveTo(226.92516, 131.40956, 225.71782, 119.28092, 223.3125, 107.34375);
        path.curveTo(222.10027, 101.32765, 220.56084, 95.37669, 218.8125, 89.6875);
        path.curveTo(233.78587, 87.41467, 244.51566, 83.53095, 247.5625, 79.28125);
        path.closePath();
        path.moveTo(255.59375, 126.5625);
        path.curveTo(252.24585, 145.40305, 241.464, 161.68222, 226.375, 172.21875);
        path.curveTo(225.97823, 168.92972, 225.45413, 165.66461, 224.78125, 162.46875);
        path.curveTo(225.26833, 160.34448, 225.64977, 158.07169, 225.96875, 155.65625);
        path.curveTo(226.3035, 153.12154, 226.5458, 150.42346, 226.6875, 147.625);
        path.curveTo(241.39175, 142.68642, 251.95264, 134.99126, 255.59375, 126.5625);
        path.closePath();
        path.moveTo(14.625, 132.0625);
        path.curveTo(5.9410377, 148.07262, 1.0, 166.41145, 1.0, 185.90625);
        path.curveTo(1.0, 188.08694, 1.065737, 190.25603, 1.1875, 192.40625);
        path.curveTo(3.3979993, 199.02866, 8.5479355, 205.45178, 16.24944, 211.19183);
        path.curveTo(23.950945, 216.93188, 34.197968, 221.98438, 46.21875, 225.96875);
        path.curveTo(46.397163, 209.61298, 48.396095, 191.59692, 52.15625, 173.875);
        path.curveTo(53.97863, 165.28596, 56.204166, 156.77338, 58.75, 148.59375);
        path.curveTo(36.03598, 144.86983, 19.726528, 138.75955, 14.625, 132.0625);
        path.closePath();
        path.moveTo(156.03125, 150.40625);
        path.curveTo(129.84764, 153.42545, 98.4731, 153.44885, 72.21875, 150.46875);
        path.curveTo(69.98964, 159.93547, 68.08202, 169.80515, 66.5625, 179.75);
        path.curveTo(63.91215, 197.09576, 62.47266, 214.55833, 62.34375, 230.40625);
        path.curveTo(78.3117, 234.04636, 96.2133, 235.9722, 114.13226, 235.9776);
        path.curveTo(132.05122, 235.9826, 149.9586, 234.06796, 165.9375, 230.4375);
        path.curveTo(165.81818, 214.53487, 164.38475, 196.996, 161.71875, 179.59375);
        path.curveTo(160.19957, 169.6773, 158.25563, 159.84451, 156.03125, 150.40625);
        path.closePath();
        path.moveTo(3.09375, 207.5625);
        path.curveTo(9.592972, 241.0716, 30.86114, 269.30954, 59.875, 285.1875);
        path.curveTo(53.9147, 278.01923, 49.571102, 266.06018, 47.53125, 250.65625);
        path.curveTo(47.09082, 247.33034, 46.787872, 243.82578, 46.5625, 240.21875);
        path.curveTo(24.63901, 232.33923, 8.887894, 220.50607, 3.09375, 207.5625);
        path.closePath();
        path.moveTo(225.0625, 207.6875);
        path.curveTo(219.2492, 220.59135, 203.53911, 232.38524, 181.6875, 240.25);
        path.curveTo(181.46059, 243.86758, 181.12965, 247.3848, 180.6875, 250.71875);
        path.curveTo(178.6539, 266.05255, 174.33324, 277.96066, 168.40625, 285.125);
        path.curveTo(197.33488, 269.25598, 218.54199, 241.10399, 225.0625, 207.6875);
        path.closePath();
        path.moveTo(165.65625, 245.03125);
        path.curveTo(149.77913, 248.96243, 131.97743, 251.04483, 114.15604, 251.05565);
        path.curveTo(96.33464, 251.06645, 78.52228, 249.00565, 62.624996, 245.09375);
        path.curveTo(62.841206, 249.56448, 63.192387, 253.82983, 63.656246, 257.84375);
        path.curveTo(65.50743, 273.86267, 69.310326, 285.71826, 74.4375, 291.84375);
        path.curveTo(86.77978, 296.46625, 100.13721, 299.0, 114.09375, 299.0);
        path.curveTo(128.1096, 299.0, 141.52026, 296.44128, 153.90625, 291.78125);
        path.curveTo(159.00786, 285.6242, 162.7845, 273.76657, 164.625, 257.78125);
        path.curveTo(165.08675, 253.77081, 165.43964, 249.49725, 165.65625, 245.03125);
        path.closePath();
    }
}
