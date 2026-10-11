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

import java.awt.BorderLayout;
import java.util.Map;
import java.util.concurrent.Callable;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;

import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.await;
import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static ai.skillroy.testjava.agent.swing.JsonCheck.obj;
import static ai.skillroy.testjava.agent.swing.LocatorTest.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The hit-test's target form (phase7-contracts.md section 3.2; fix wave FC, L1-F5): beside {@code deepest} and
 * {@code mouseTarget} at the named component's centre it answers the component's own node as {@code targetNode}, so
 * that a composite button whose centre shows a label is described as the button (the recorder's role and class); the
 * point form answers no {@code targetNode}.
 */
class HitTestTargetNodeDisplayTest extends DisplayTestBase {

    @Test
    void theTargetFormAnswersTheNamedComponentsOwnNode() throws Exception {
        final JFrame[] frame = new JFrame[1];
        final JButton[] button = new JButton[1];
        final JLabel[] label = new JLabel[1];
        onEdt(new Callable<Void>() {
            @Override
            public Void call() {
                JFrame f = new JFrame("Composite");
                JButton b = new JButton();
                b.setLayout(new BorderLayout());
                JLabel l = new JLabel("Renew", SwingConstants.CENTER);
                b.add(l, BorderLayout.CENTER);
                f.getContentPane().add(b);
                f.setBounds(420, 320, 200, 80);
                f.setVisible(true);
                frame[0] = f;
                button[0] = b;
                label[0] = l;
                return null;
            }
        });
        try {
            await("the composite frame shows", WAIT_MS, () -> frame[0].isShowing());
            settle();
            Map<String, Object> hit = call("swing.hitTest", "target", ref(button[0]));
            assertEquals("TARGET", hit.get("targetKind"));
            assertEquals(ref(button[0]), hit.get("target"));
            Map<String, Object> own = obj(hit.get("targetNode"));
            assertEquals(ref(button[0]), own.get("ref"), "the named component's own node");
            assertEquals("button", own.get("role"));
            assertEquals(ref(label[0]), obj(hit.get("deepest")).get("ref"), "a label is drawn at its centre");
            Map<String, Object> point = call("swing.hitTest", "point", map("x", 5L, "y", 5L), "space",
                    map("window", ref(frame[0])));
            assertFalse(point.containsKey("targetNode"), "the point form answers none: " + point.keySet());
        } finally {
            onEdt(new Callable<Void>() {
                @Override
                public Void call() {
                    frame[0].dispose();
                    return null;
                }
            });
        }
    }
}
