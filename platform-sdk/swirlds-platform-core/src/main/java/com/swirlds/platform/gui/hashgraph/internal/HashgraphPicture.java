// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.hashgraph.internal;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.platform.gui.hashgraph.HashgraphGuiConstants.HASHGRAPH_PICTURE_FONT;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.platform.Consensus;
import com.swirlds.platform.gui.hashgraph.HashgraphGuiConstants;
import com.swirlds.platform.gui.hashgraph.HashgraphGuiSource;
import com.swirlds.platform.gui.hashgraph.HashgraphPictureOptions;
import com.swirlds.platform.internal.EventImpl;
import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Stroke;
import java.awt.event.ItemEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.Serial;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.JPanel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.EventConstants;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterUtils;

/**
 * This panel has the hashgraph picture, and appears in the window to the right of all the settings.
 */
public class HashgraphPicture extends JPanel {
    @Serial
    private static final long serialVersionUID = 1L;

    private static final Logger logger = LogManager.getLogger(HashgraphPicture.class);
    private final HashgraphGuiSource hashgraphSource;
    private final HashgraphPictureOptions options;
    private final EventSelector selector;
    private PictureMetadata pictureMetadata;
    /** used to store an image when the freeze checkbox is checked */
    private BufferedImage image = null;

    private RosterMetadata nonExpandedMetadata;
    private RosterMetadata expandedMetadata;

    /** used to store coordinates for branched events with a given generation for each forking node */
    private final Map<Long, Map<Long, GenerationCoordinates>> nodeIdToBranchIndexToCoordinates = new HashMap<>();

    public HashgraphPicture(final HashgraphGuiSource hashgraphSource, final HashgraphPictureOptions options) {
        this.hashgraphSource = hashgraphSource;
        this.options = options;
        this.selector = new EventSelector();
        this.addMouseListener(selector);
        createMetadata();
    }

    private void createMetadata() {
        if ((expandedMetadata == null || nonExpandedMetadata == null) && hashgraphSource.isReady()) {
            expandedMetadata = new RosterMetadata(hashgraphSource.getRoster(), true);
            nonExpandedMetadata = new RosterMetadata(hashgraphSource.getRoster(), false);
        }
    }

    @Override
    public void paintComponent(final Graphics g) {
        super.paintComponent(g);
        ((Graphics2D) g).setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        try {
            if (image != null) {
                g.drawImage(image, 0, 0, null);
                return;
            }
            if (!hashgraphSource.isReady()) {
                return;
            }
            createMetadata();
            g.setFont(HASHGRAPH_PICTURE_FONT);
            final FontMetrics fm = g.getFontMetrics();
            final Roster roster = hashgraphSource.getRoster();
            final int numMem = roster.rosterEntries().size();
            final RosterMetadata currentMetadata = options.isExpanded() ? expandedMetadata : nonExpandedMetadata;

            List<EventImpl> events;
            if (options.displayLatestEvents()) {
                final long startGen = Math.max(
                        hashgraphSource.getMaxGeneration() - options.getNumGenerationsDisplay() + 1,
                        EventConstants.FIRST_GENERATION);
                options.setStartGeneration(startGen);
                events = hashgraphSource.getEvents(startGen, options.getNumGenerationsDisplay());
            } else {
                events = hashgraphSource.getEvents(options.getStartGeneration(), options.getNumGenerationsDisplay());
            }
            // in case the state has events from creators that don't exist, don't show them
            if (events == null) { // in case a screen refresh happens before any events
                return;
            }
            events = events.stream()
                    .filter(e -> RosterUtils.getIndex(roster, e.getCreatorId().id()) != -1)
                    .filter(e -> RosterUtils.getIndex(roster, e.getCreatorId().id()) < numMem)
                    .toList();

            pictureMetadata = new PictureMetadata(
                    fm, this.getSize(), currentMetadata, events, hashgraphSource, nodeIdToBranchIndexToCoordinates);

            selector.setMetadata(pictureMetadata);
            selector.setEventsInPicture(events);

            g.setColor(Color.BLACK);

            for (int i = 0; i < currentMetadata.getNumColumns(); i++) {
                final String name = currentMetadata.getLabel(i);

                // gap between columns
                final int betweenGap = pictureMetadata.getGapBetweenColumns();
                // gap between leftmost column and left edge (and similar on right)
                final int sideGap = pictureMetadata.getSideGap();
                final int x = sideGap + (i) * betweenGap;
                g.drawLine(x, pictureMetadata.getYmin(), x, pictureMetadata.getYmax());
                final Rectangle2D rect = fm.getStringBounds(name, g);
                g.drawString(
                        name, (int) (x - rect.getWidth() / 2), (int) (pictureMetadata.getYmax() + rect.getHeight()));
            }

            final int d = pictureMetadata.getD();

            if (nodeIdToBranchIndexToCoordinates.isEmpty()) {
                final Set<NodeId> nodeIdSet = roster.rosterEntries().stream()
                        .map(RosterEntry::nodeId)
                        .map(NodeId::of)
                        .collect(Collectors.toSet());
                for (final NodeId nodeId : nodeIdSet) {
                    nodeIdToBranchIndexToCoordinates.put(nodeId.id(), new HashMap<>());
                }
            }

            // for each event, draw 2 downward lines to its parents
            for (final EventImpl event : events) {
                drawLinksToParents(g, event);
            }

            // for each event, draw its circle
            for (final EventImpl event : events) {
                drawEventCircle(g, event, options, d);
            }

            final List<EventImpl> selectedEvents =
                    events.stream().filter(selector::isSelected).toList();
            if (!selectedEvents.isEmpty()) {
                final EventImpl selectedEvent = selectedEvents.getFirst();
                if (selectedEvent != null) {
                    // if we have a selected event draw the circle and its parent links last, so that all labels and
                    // lines connected to it can be easily seen
                    drawLinksToParents(g, selectedEvent);
                    drawEventCircle(g, selectedEvent, options, d);
                }
            }
        } catch (final Exception e) {
            logger.error(EXCEPTION.getMarker(), "error while painting", e);
        }
    }

    /**
     * Draws a border around the event circle.
     *
     * @param g    the graphics context
     * @param d    the diameter of the event circle
     * @param event the selected event
     * @param borderColor the color of the border
     */
    private void drawBorderAroundEvent(final Graphics g, final int d, final EventImpl event, final Color borderColor) {
        final int xPos =
                pictureMetadata.xpos(event.getOtherParent() != null ? event.getOtherParent() : event, event) - d / 2;
        final int yPos = pictureMetadata.ypos(event) - d / 2;
        g.setColor(borderColor);
        g.fillOval(xPos - 5, yPos - 5, d + 10, d + 10);
    }

    private void drawLinksToParents(final Graphics g, final EventImpl event) {
        final int d = pictureMetadata.getD();
        final Graphics2D g2d = (Graphics2D) g;
        Stroke savedStroke = null;
        g.setColor(HashgraphGuiUtils.eventColor(event, options));
        boolean selectedLines = selector.isSelected(event);
        if (selectedLines) {
            g.setColor(Color.MAGENTA);
            savedStroke = g2d.getStroke();
            g2d.setStroke(new BasicStroke(3));
        }

        final EventImpl e1 = event.getSelfParent();
        EventImpl e2 = event.getOtherParent();
        final Roster roster = hashgraphSource.getRoster();
        if (e2 != null
                && (RosterUtils.getIndex(roster, e2.getCreatorId().id()) == -1
                        || RosterUtils.getIndex(roster, e2.getCreatorId().id())
                                >= roster.rosterEntries().size())) {
            // if the creator of the other parent has been removed,
            // treat it as if there is no other parent
            e2 = null;
        }

        if (e1 != null && e1.getNGen() >= pictureMetadata.getMinGen()) {
            g.drawLine(
                    pictureMetadata.xpos(e2, event),
                    pictureMetadata.ypos(event),
                    pictureMetadata.xpos(e2, e1),
                    pictureMetadata.ypos(e1));

            if (selectedLines) {
                final Color currentColor = g.getColor();
                drawBorderAroundEvent(g, d, e1, Color.MAGENTA);
                drawEventCircle(g, e1, options, d);
                g.setColor(currentColor);
            }
        }
        if (e2 != null && e2.getNGen() >= pictureMetadata.getMinGen()) {
            g.drawLine(
                    pictureMetadata.xpos(e2, event),
                    pictureMetadata.ypos(event),
                    pictureMetadata.xpos(event, e2),
                    pictureMetadata.ypos(e2));

            if (selectedLines) {
                final Color currentColor = g.getColor();
                drawBorderAroundEvent(g, d, e2, Color.MAGENTA);
                drawEventCircle(g, e2, options, d);
                g.setColor(currentColor);
            }
        }

        if (selectedLines) {
            g2d.setStroke(savedStroke);
        }
    }

    private void drawEventCircle(
            final Graphics g, final EventImpl event, final HashgraphPictureOptions options, final int d) {
        final Consensus consensus = hashgraphSource.getEventStorage().getConsensus();
        final FontMetrics fm = g.getFontMetrics();
        final int fa = fm.getMaxAscent();
        final int fd = fm.getMaxDescent();
        final EventImpl e2 = event.getOtherParent() != null
                        && RosterUtils.getIndex(
                                        hashgraphSource.getRoster(),
                                        event.getOtherParent().getCreatorId().id())
                                != -1
                ? event.getOtherParent()
                : null;
        final Color color;
        if (selector.isSelected(event)) {
            color = Color.MAGENTA;
        } else if (selector.isStronglySeen(event)) {
            color = Color.CYAN;
        } else {
            color = HashgraphGuiUtils.eventColor(event, options);
        }

        final int xPos = pictureMetadata.xpos(e2, event) - d / 2;
        final int yPos = pictureMetadata.ypos(event) - d / 2;

        if (selector.isSelected(event)) {
            drawBorderAroundEvent(g, d, event, Color.GREEN);
        }

        g.setColor(color);
        g.fillOval(xPos, yPos, d, d);
        g.setFont(g.getFont().deriveFont(Font.BOLD));

        String s = "";

        if (options.writeRoundCreated()) {
            s += " " + event.getRoundCreated();
        }
        if (options.writeVote() && event.isWitness()) {
            for (int i = 0; i < event.getVotesSize(); i++) {
                // showing T or F from true/false for readability on the picture
                final String vote = event.getVote(i) ? "T" : "F";
                s += vote;
            }
        }
        if (options.writeEventHash()) {
            // showing first two characters from the hash of the event
            s += " h:" + event.getBaseHash().toString().substring(0, 2);
        }
        if (options.writeRoundReceived() && event.getRoundReceived() > 0) {
            s += " " + event.getRoundReceived();
        }
        // if not consensus, then there's no order yet
        if (options.writeConsensusOrder() && event.isConsensus()) {
            s += " " + event.getBaseEvent().getConsensusOrder();
        }
        if (options.writeConsensusTimeStamp()) {
            final Instant t = event.getConsensusTimestamp();
            if (t != null) {
                s += " " + HashgraphGuiConstants.FORMATTER.format(t);
            }
        }
        if (options.writeNGen()) {
            s += " " + event.getNGen();
        }

        if (options.writeBirthRound()) {
            s += " " + event.getBirthRound();
        }

        final GossipEvent gossipEvent = event.getBaseEvent().getGossipEvent();
        if (options.writeBranches()
                && hashgraphSource.getEventStorage().getBranchedEventsMetadata().containsKey(gossipEvent)) {
            s += " " + "\\/ "
                    + hashgraphSource
                            .getEventStorage()
                            .getBranchedEventsMetadata()
                            .get(gossipEvent)
                            .branchIndex();
        }

        if (options.writeDeGen()) {
            s += " " + event.getDeGen();
        }
        if (!s.isEmpty()) {
            final Rectangle2D rect = fm.getStringBounds(s, g);

            final int x = (int) (pictureMetadata.xpos(e2, event) - rect.getWidth() / 2. - fa / 4.);
            final int y = (int) (pictureMetadata.ypos(event) + rect.getHeight() / 2. - fd / 2);
            g.setColor(HashgraphGuiConstants.LABEL_OUTLINE);
            g.drawString(s, x - 1, y - 1);
            g.drawString(s, x + 1, y - 1);
            g.drawString(s, x - 1, y + 1);
            g.drawString(s, x + 1, y + 1);
            g.setColor(color);
            g.drawString(s, x, y);
        }
    }

    public void freezeChanged(final ItemEvent e) {
        if (e.getStateChange() == ItemEvent.SELECTED) {
            try { // capture a bitmap of "picture" from the screen
                image = (new Robot())
                        .createScreenCapture(new Rectangle(
                                this.getLocationOnScreen(),
                                this.getVisibleRect().getSize()));
                // to write the image to disk:
                // ImageIO.write(image, "jpg", new File("image.jpg"));
            } catch (final AWTException err) {
                // ignore exception
            }
        } else if (e.getStateChange() == ItemEvent.DESELECTED) {
            image = null; // erase the saved image, stop freezing
        }
    }
}
