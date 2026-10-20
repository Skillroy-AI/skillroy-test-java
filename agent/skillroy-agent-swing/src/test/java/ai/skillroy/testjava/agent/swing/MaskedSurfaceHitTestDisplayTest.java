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

import ai.skillroy.testjava.agent.api.spi.SurfaceSource;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.MouseAdapter;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.await;
import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static ai.skillroy.testjava.agent.swing.LocatorTest.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 8 security fix S166 (FE; phase8-contracts.md 5.2, phase7-contracts.md 3.6, 4.8): a surface its module masks (a
 * masked embedded browser's view, here a {@code BrowserMask}'s TRUE stands behind the flag) makes {@code swing.hitTest}
 * answer {@code masked: true} for a point on it and for the target form that names its component (what the vision
 * tools' {@code type} and {@code press} ask of a focused view), as for a component a {@code CaptureMask} names; also when
 * the point's target is a listening ancestor of the view. The control: the same surface unmasked answers
 * {@code masked: false}.
 */
class MaskedSurfaceHitTestDisplayTest extends DisplayTestBase {

    @Test
    void aHitOnASurfaceItsModuleMasksIsMasked() throws Exception {
        final JFrame[] frame = new JFrame[1];
        final JPanel[] holder = new JPanel[1];
        final JLabel[] view = new JLabel[1];
        onEdt(new Callable<Void>() {
            @Override
            public Void call() {
                JFrame f = new JFrame("Loan desk page");
                JPanel h = new JPanel(new BorderLayout());
                h.addMouseListener(new MouseAdapter() {
                });
                JLabel v = new JLabel("member page");
                v.setOpaque(true);
                v.setBackground(Color.BLUE);
                h.add(v, BorderLayout.CENTER);
                f.getContentPane().add(h);
                f.setBounds(460, 340, 240, 120);
                f.setVisible(true);
                frame[0] = f;
                holder[0] = h;
                view[0] = v;
                return null;
            }
        });
        final boolean[] masked = {false};
        AutoCloseable source = ctx.spi.register(SurfaceSource.class, () -> Collections.singletonList(
                new SurfaceSource.Surface(view[0], "WEB", "b-9", masked[0])));
        try {
            await("the page frame shows", WAIT_MS, () -> frame[0].isShowing());
            settle();
            Map<String, Object> point = map("x", 60L, "y", 40L);
            Map<String, Object> space = map("window", ref(frame[0]));
            Map<String, Object> open = call("swing.hitTest", "point", point, "space", space);
            assertEquals("WEB", open.get("surface"), String.valueOf(open));
            assertEquals(Boolean.FALSE, open.get("masked"), "the control: an unmasked surface");
            assertEquals(Boolean.FALSE, call("swing.hitTest", "target", ref(view[0])).get("masked"));

            masked[0] = true;
            Map<String, Object> hit = call("swing.hitTest", "point", point, "space", space);
            assertEquals(Boolean.TRUE, hit.get("masked"), "a point on a masked surface: " + hit);
            assertEquals("WEB", hit.get("surface"));
            assertEquals(Boolean.TRUE, call("swing.hitTest", "target", ref(view[0])).get("masked"),
                    "the target form names the masked view itself");
            assertEquals(Boolean.TRUE, call("swing.hitTest", "target", ref(holder[0])).get("masked"),
                    "the target form names its listening ancestor, whose centre shows the masked view");
        } finally {
            source.close();
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
