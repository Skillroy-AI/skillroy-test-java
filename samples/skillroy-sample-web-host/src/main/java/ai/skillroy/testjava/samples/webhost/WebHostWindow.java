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


package ai.skillroy.testjava.samples.webhost;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.logging.Logger;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.WindowConstants;

/**
 * The host's one window: a {@link JFrame} titled {@value #TITLE} with a bar on top (a {@link JButton}
 * {@value #SWAP} that shows the other card, a {@link JButton} {@value #NOTICE} that shows a modal notice over the
 * page, and a label "Showing: front|back") and a {@link CardLayout} of the two browsers' components below it (the
 * deck, named {@value #DECK}), card {@code front} shown and card {@code back} hidden. The two browser components are
 * named after their cards.
 *
 * <p>The notice (Phase 7 follow-up, gap 95: a modal dialog over a real page for the web host's checks) is a modal
 * {@link JOptionPane} message dialog titled {@value #NOTICE_TITLE} with one OK button, centred on the frame and so
 * over the page; the button's action posts it ({@code invokeLater}), so that the click's own event returns at once.
 *
 * <p>Kept out of {@link WebHostApp} so that nothing of Swing is loaded before the CEF application is built.
 * Confined to the event dispatch thread: every method must be called there.
 */
final class WebHostWindow {

    /** The frame's title. */
    static final String TITLE = "Web host";
    /** The swap button's text. */
    static final String SWAP = "Swap";
    /** The notice button's text (its name is {@code notice}). */
    static final String NOTICE = "Notice";
    /** The notice's title. */
    static final String NOTICE_TITLE = "Loan notice";
    /** The notice's text. */
    static final String NOTICE_TEXT = "Check the loan terms before you borrow.";
    /** The name of the panel that holds the two cards. */
    static final String DECK = "deck";
    static final int WIDTH = 900;
    static final int HEIGHT = 700;

    private static final Logger LOG = Logger.getLogger(WebHostApp.class.getName());

    private final JFrame frame = new JFrame(TITLE);
    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final JLabel showing = new JLabel();
    private String current;

    private WebHostWindow(Component front, Component back, final Runnable closeRequested) {
        frame.setName("webhost");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeRequested.run();
            }
        });
        JButton swap = new JButton(SWAP);
        swap.setName("swap");
        swap.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                swap();
            }
        });
        JButton notice = new JButton(NOTICE);
        notice.setName("notice");
        notice.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                EventQueue.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        showNotice();
                    }
                });
            }
        });
        showing.setName("showing");
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(swap);
        bar.add(notice);
        bar.add(showing);
        deck.setName(DECK);
        front.setName(WebHostPages.FRONT);
        back.setName(WebHostPages.BACK);
        deck.add(front, WebHostPages.FRONT);
        deck.add(back, WebHostPages.BACK);
        frame.getContentPane().add(bar, BorderLayout.NORTH);
        frame.getContentPane().add(deck, BorderLayout.CENTER);
        frame.setSize(WIDTH, HEIGHT);
        frame.setLocation(0, 0);
        show(WebHostPages.FRONT);
        frame.setVisible(true);
    }

    /** Builds the window and shows it with the front card visible. EDT only. */
    static WebHostWindow open(Component front, Component back, Runnable closeRequested) {
        return new WebHostWindow(front, back, closeRequested);
    }

    /** Shows the other card. EDT only. */
    void swap() {
        show(WebHostPages.FRONT.equals(current) ? WebHostPages.BACK : WebHostPages.FRONT);
        LOG.info("web host: showing the " + current + " card");
    }

    /**
     * Shows the notice, a modal message dialog over the frame, and returns when it was answered or closed. EDT only
     * (the dialog runs its own event loop meanwhile).
     */
    void showNotice() {
        LOG.info("web host: showing the notice");
        JOptionPane.showMessageDialog(frame, NOTICE_TEXT, NOTICE_TITLE, JOptionPane.INFORMATION_MESSAGE);
        LOG.info("web host: the notice was closed");
    }

    /** @return the card shown: {@code front} or {@code back}. EDT only. */
    String showing() {
        return current;
    }

    JFrame frame() {
        return frame;
    }

    /** Disposes of the frame. EDT only. */
    void dispose() {
        frame.dispose();
    }

    private void show(String card) {
        cards.show(deck, card);
        current = card;
        showing.setText("Showing: " + card);
    }
}
