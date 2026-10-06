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

package ai.skillroy.testjava.agent.swing;

import ai.skillroy.testjava.agent.api.spi.ActionFirer;
import ai.skillroy.testjava.agent.api.spi.DialogInfo;
import ai.skillroy.testjava.agent.api.spi.DialogTextExtractor;
import ai.skillroy.testjava.agent.api.spi.LocatorResolver;
import ai.skillroy.testjava.agent.api.spi.NameEnricher;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * Awkward widgets of the kind real target applications have, and the SPI implementations a domain module would
 * register for them.
 */
final class TestWidgets {

    private TestWidgets() {
    }

    /**
     * An icon-only button with no text and no accessible name whose action is deferred: a press starts a 60 ms timer
     * and the action is delivered only if the key owns the focus then (unless {@code focusGated} is false). A naive
     * {@code doClick()} on an unfocused window therefore does nothing; {@link #perform()} delivers directly.
     */
    static final class GatedKey extends JButton {
        private static final long serialVersionUID = 1L;
        final String actionId;
        volatile boolean focusGated = true;
        final AtomicInteger presses = new AtomicInteger();
        final AtomicInteger delivered = new AtomicInteger();

        GatedKey(String actionId) {
            super(new SquareIcon());
            this.actionId = actionId;
            addActionListener(e -> {
                presses.incrementAndGet();
                Timer timer = new Timer(60, ev -> {
                    if (!focusGated || hasFocus()) {
                        delivered.incrementAndGet();
                    }
                });
                timer.setRepeats(false);
                timer.start();
            });
        }

        void perform() {
            delivered.incrementAndGet();
        }
    }

    /** A plain square icon. */
    static final class SquareIcon implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(Color.DARK_GRAY);
            g.fillRect(x, y, getIconWidth(), getIconHeight());
        }

        @Override
        public int getIconWidth() {
            return 24;
        }

        @Override
        public int getIconHeight() {
            return 24;
        }
    }

    /** A custom-painted "button": a JComponent that draws its label and answers its dialog on mouse release. */
    static final class PaintedKey extends JComponent {
        private static final long serialVersionUID = 1L;
        final String label;
        private final PaintedPrompt prompt;

        PaintedKey(String label, PaintedPrompt prompt) {
            this.label = label;
            this.prompt = prompt;
            setPreferredSize(new Dimension(80, 30));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent e) {
                    choose();
                }
            });
        }

        void choose() {
            prompt.answer(label);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(Color.LIGHT_GRAY);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(Color.BLACK);
            g.drawString(label, 10, 20);
        }
    }

    /** An undecorated modal dialog whose message is painted with drawString (no labels, no JOptionPane). */
    static final class PaintedPrompt extends JDialog {
        private static final long serialVersionUID = 1L;
        final String message;
        final List<String> buttons;
        final AtomicReference<String> answer = new AtomicReference<String>();

        PaintedPrompt(Frame owner, final String message, String... buttons) {
            super(owner, true);
            this.message = message;
            this.buttons = Collections.unmodifiableList(Arrays.asList(buttons));
            setUndecorated(true);
            JPanel content = new JPanel(new FlowLayout());
            content.add(new JComponent() {
                private static final long serialVersionUID = 1L;

                {
                    setPreferredSize(new Dimension(260, 30));
                }

                @Override
                protected void paintComponent(Graphics g) {
                    g.setColor(Color.BLACK);
                    g.drawString(message, 5, 20);
                }
            });
            for (String b : buttons) {
                content.add(new PaintedKey(b, this));
            }
            setContentPane(content);
            setSize(320, 120);
            setLocation(40, 40);
        }

        void answer(String choice) {
            answer.set(choice);
            dispose();
        }
    }

    /** Names GatedKeys by their action id and PaintedKeys by their label. */
    static final class Enricher implements NameEnricher {
        @Override
        public String nameFor(Component c) {
            if (c instanceof GatedKey) {
                return ((GatedKey) c).actionId;
            }
            if (c instanceof PaintedKey) {
                return ((PaintedKey) c).label;
            }
            return null;
        }
    }

    /** Locator strategy {@code action}: GatedKeys by action id. */
    static final class ActionLocator implements LocatorResolver {
        @Override
        public Set<String> strategies() {
            return Collections.singleton("action");
        }

        @Override
        public List<Component> resolve(String strategy, String value, Component root) {
            List<Component> out = new ArrayList<Component>();
            collect(root, value, out);
            return out;
        }

        private static void collect(Component c, String value, List<Component> out) {
            if (c instanceof GatedKey && ((GatedKey) c).actionId.equals(value)) {
                out.add(c);
            }
            if (c instanceof Container) {
                for (Component child : ((Container) c).getComponents()) {
                    collect(child, value, out);
                }
            }
        }
    }

    /** The ACTION rung for GatedKeys and PaintedKeys. */
    static final class Firer implements ActionFirer {
        final AtomicInteger fired = new AtomicInteger();

        @Override
        public boolean canFire(Component c) {
            return c instanceof GatedKey || c instanceof PaintedKey;
        }

        @Override
        public void fire(Component c) {
            fired.incrementAndGet();
            if (c instanceof GatedKey) {
                ((GatedKey) c).perform();
            } else {
                ((PaintedKey) c).choose();
            }
        }
    }

    /** Reads PaintedPrompts. */
    static final class PromptExtractor implements DialogTextExtractor {
        @Override
        public DialogInfo extract(java.awt.Window window) {
            if (!(window instanceof PaintedPrompt)) {
                return null;
            }
            PaintedPrompt p = (PaintedPrompt) window;
            return new DialogInfo("test-prompt", null, p.message, p.buttons);
        }
    }
}
