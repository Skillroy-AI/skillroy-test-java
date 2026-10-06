/*
 * Copyright 2026 Mike Mills.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.skillroy.testjava.samples.lending;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Objects;
import java.util.logging.Logger;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * A keypad key: a custom-painted button that shows only an icon (drawn in code), with no text, no accessible name
 * and no component name. Its {@link #actionId()} (for example {@code "DUE"}) is what it does.
 *
 * <p><b>Debounce and focus.</b> A press (mouse click, Space or Enter while focused, or {@link #doClick()}) starts a
 * {@value #DEBOUNCE_MS} ms {@link javax.swing.Timer}; further presses restart it. When it expires the action is
 * delivered only if the key owns the focus or {@link #isFocusGated()} is false (it is true by default). A mouse press
 * requests the focus first, as a normal button does; {@link #doClick()} does not, so a programmatic click on a key
 * that does not own the focus does nothing. {@link #perform()} delivers the action at once, without debounce or
 * focus check.
 *
 * <p><b>Threading.</b> Like every Swing component: create it and call its methods on the event dispatch thread.
 * Action listeners are called on the event dispatch thread.
 */
public final class IconKey extends JComponent {

    /** Debounce time of a press, in milliseconds. */
    public static final int DEBOUNCE_MS = 60;

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(IconKey.class.getName());

    private final String actionId;
    private final Timer debounce;
    private boolean focusGated = true;
    private boolean armed;

    /** @param actionId what the key does, for example {@code "DUE"} */
    public IconKey(String actionId) {
        this.actionId = Objects.requireNonNull(actionId, "actionId");
        setFocusable(true);
        setRequestFocusEnabled(true);
        setOpaque(true);
        setPreferredSize(new Dimension(96, 64));
        debounce = new Timer(DEBOUNCE_MS, e -> debounceExpired());
        debounce.setRepeats(false);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!isEnabled() || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                if (isRequestFocusEnabled()) {
                    requestFocusInWindow();
                }
                armed = true;
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (!armed) {
                    return;
                }
                armed = false;
                repaint();
                if (contains(e.getPoint())) {
                    doClick();
                }
            }
        });
        addFocusListener(new FocusListener() {
            @Override
            public void focusGained(FocusEvent e) {
                repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                repaint();
            }
        });
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "press");
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "press");
        getActionMap().put("press", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                doClick();
            }
        });
    }

    /** @return what the key does, for example {@code "DUE"} (a {@link KeyAction} name) */
    public String actionId() {
        return actionId;
    }

    /** @return true if a debounced press is delivered only while the key owns the focus (default) */
    public boolean isFocusGated() {
        return focusGated;
    }

    /** @param gated false to deliver debounced presses whether or not the key owns the focus */
    public void setFocusGated(boolean gated) {
        this.focusGated = gated;
    }

    /**
     * A programmatic press, like {@code AbstractButton.doClick()}: starts (or restarts) the debounce timer. It does
     * not request the focus. Event dispatch thread.
     */
    public void doClick() {
        if (isEnabled()) {
            debounce.restart();
        }
    }

    /** Delivers the action to the listeners now, without debounce or focus check. Event dispatch thread. */
    public void perform() {
        fireAction();
    }

    /** Adds a listener called when the key's action is delivered; the action command is {@link #actionId()}. */
    public void addActionListener(ActionListener listener) {
        listenerList.add(ActionListener.class, listener);
    }

    public void removeActionListener(ActionListener listener) {
        listenerList.remove(ActionListener.class, listener);
    }

    public ActionListener[] getActionListeners() {
        return listenerList.getListeners(ActionListener.class);
    }

    private void debounceExpired() {
        if (!focusGated || isFocusOwner()) {
            fireAction();
        } else {
            LOG.fine("key " + actionId + " pressed without focus; ignored");
        }
    }

    private void fireAction() {
        ActionEvent event = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, actionId);
        for (ActionListener listener : getActionListeners()) {
            listener.actionPerformed(event);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(getParent() != null ? getParent().getBackground() : Color.LIGHT_GRAY);
            g2.fillRect(0, 0, w, h);
            g2.setColor(armed ? new Color(0x9db8d2) : isEnabled() ? new Color(0xdfe7ef) : new Color(0xeeeeee));
            g2.fillRoundRect(2, 2, w - 5, h - 5, 12, 12);
            g2.setColor(isFocusOwner() ? new Color(0x1f5fa8) : new Color(0x7a8a99));
            g2.drawRoundRect(2, 2, w - 5, h - 5, 12, 12);
            if (isFocusOwner()) {
                g2.drawRoundRect(4, 4, w - 9, h - 9, 10, 10);
            }
            IconPainter.paint(g2, actionId, w, h, isEnabled() ? new Color(0x23313f) : Color.GRAY);
        } finally {
            g2.dispose();
        }
    }

    /**
     * An accessible context with the push-button role and, deliberately, no name: the key is an icon-only button.
     */
    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleIconKey();
        }
        return accessibleContext;
    }

    /** Accessible context of an {@link IconKey}: role {@code PUSH_BUTTON}, no accessible name. */
    protected final class AccessibleIconKey extends AccessibleJComponent {
        private static final long serialVersionUID = 1L;

        @Override
        public AccessibleRole getAccessibleRole() {
            return AccessibleRole.PUSH_BUTTON;
        }
    }
}
