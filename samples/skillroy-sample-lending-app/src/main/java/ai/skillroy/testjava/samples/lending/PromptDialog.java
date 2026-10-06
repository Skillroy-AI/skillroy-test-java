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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.WindowConstants;

/**
 * The desk's modal prompt: an undecorated, application-modal {@link JDialog} whose text is painted by a custom
 * component and whose buttons are custom-painted {@link PromptButton}s; it contains no {@code JLabel}, no
 * {@code JOptionPane} and no {@code AbstractButton}. The accessors {@link #promptId()}, {@link #promptText()},
 * {@link #buttonLabels()} and {@link #buttons()} let code that holds the window read and answer it.
 *
 * <p>Pressing a button answers the prompt through the {@link PromptService} and closes the dialog. Enter presses the
 * first button. Closing the window (a {@code WINDOW_CLOSING} event, or Escape) presses the last button, which is the
 * cancelling choice of every prompt ({@code OK} when it is the only one).
 *
 * <p>Event dispatch thread only. {@link SwingPromptView} creates and shows it.
 */
public final class PromptDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final transient Prompt prompt;
    private final transient PromptService service;
    private final List<PromptButton> buttons;

    /**
     * @param owner owning window, may be null
     * @param prompt the prompt to show
     * @param service where the answer goes
     */
    public PromptDialog(Window owner, Prompt prompt, PromptService service) {
        super(owner, ModalityType.APPLICATION_MODAL);
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        this.service = Objects.requireNonNull(service, "service");
        setUndecorated(true);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        List<PromptButton> list = new ArrayList<PromptButton>();
        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 4));
        row.setOpaque(false);
        for (String label : prompt.buttons()) {
            PromptButton button = new PromptButton(label, this::pressed);
            list.add(button);
            row.add(button);
        }
        buttons = Collections.unmodifiableList(list);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBackground(new Color(0xf4f1e8));
        content.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x56687a), 2),
            BorderFactory.createEmptyBorder(12, 12, 12, 12)));
        content.add(new PromptTextPane(prompt.text()), BorderLayout.CENTER);
        content.add(row, BorderLayout.SOUTH);
        setContentPane(content);

        bindKey(KeyEvent.VK_ENTER, "first", 0);
        bindKey(KeyEvent.VK_ESCAPE, "last", buttons.size() - 1);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                buttons.get(buttons.size() - 1).press();
            }
        });
        pack();
        setLocationRelativeTo(owner);
    }

    private void bindKey(int keyCode, String name, int buttonIndex) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(keyCode, 0), name);
        getRootPane().getActionMap().put(name, new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                buttons.get(buttonIndex).press();
            }
        });
    }

    private void pressed(PromptButton button) {
        // A dialog can outlive its prompt for a moment (answered through the service, not yet hidden). The check is
        // by identity because the next prompt may have the same id, and a stale press must not answer that one.
        if (service.current() == prompt) {
            try {
                service.answer(prompt.id(), button.label());
            } catch (IllegalArgumentException e) {
                return;
            }
        }
        dispose();
    }

    /** @return the prompt shown */
    public Prompt prompt() {
        return prompt;
    }

    /** @return the prompt's id, for example {@code "unknown-item"} */
    public String promptId() {
        return prompt.id();
    }

    /** @return the text painted in the dialog */
    public String promptText() {
        return prompt.text();
    }

    /** @return the button labels in display order */
    public List<String> buttonLabels() {
        return prompt.buttons();
    }

    /** @return the buttons in display order */
    public List<PromptButton> buttons() {
        return buttons;
    }
}
