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

import ai.skillroy.testjava.protocol.Json;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableColumnModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;

/**
 * {@code swing.rendering} (phase7-contracts.md section 3.8, A63, D350): the rendering inputs a pixel baseline is
 * keyed by. The render key hashes what renders: the Java specification version, the look and feel, the effective
 * antialiasing ({@code awt.font.desktophints} as sorted {@code key=value} strings, and the
 * {@code awt.useSystemAAFontSettings} property), the screen scale and the hash of a probe render (a fixed 320 x 120
 * off-screen panel with a plain and a bold label, a text field, a table header of two columns, a button and an
 * antialiased filled ellipse, painted with {@code printAll}); never the vendor, the runtime's full version, the OS or
 * font names (BS11).
 *
 * <p>Stateless. {@link #read} must run on the EDT; {@link #renderKey} on any thread.
 */
final class Rendering {

    /** The fields of the result the key hashes, in this order. */
    static final List<String> KEYED = Collections.unmodifiableList(Arrays.asList("jdk.specification", "lookAndFeel",
            "antialiasing", "screenScale", "probeHash"));
    /** The probe render's size. */
    static final int PROBE_WIDTH = 320;
    static final int PROBE_HEIGHT = 120;

    private Rendering() {
    }

    /** The rendering inputs and the render key. EDT only. */
    static Map<String, Object> read() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        Map<String, Object> jdk = new LinkedHashMap<String, Object>();
        jdk.put("vendor", System.getProperty("java.vendor"));
        jdk.put("version", System.getProperty("java.runtime.version"));
        jdk.put("specification", System.getProperty("java.specification.version"));
        m.put("jdk", jdk);
        Map<String, Object> os = new LinkedHashMap<String, Object>();
        os.put("name", System.getProperty("os.name"));
        os.put("version", System.getProperty("os.version"));
        os.put("arch", System.getProperty("os.arch"));
        m.put("os", os);
        m.put("lookAndFeel", UIManager.getLookAndFeel() == null ? null
                : UIManager.getLookAndFeel().getClass().getName());
        Map<String, Object> aa = new LinkedHashMap<String, Object>();
        aa.put("desktopHints", desktopHints());
        aa.put("property", System.getProperty("awt.useSystemAAFontSettings"));
        m.put("antialiasing", aa);
        m.put("screenScale", Double.valueOf(screenScale()));
        m.put("probeHash", Capture.hash(probe()));
        m.put("keyed", new ArrayList<Object>(KEYED));
        m.put("renderKey", renderKey(m));
        return m;
    }

    /**
     * The render key of a {@code swing.rendering} result: the first 16 hex digits of the SHA-256 over the canonical
     * JSON of the keyed fields ({@code jdk.specification} is the {@code specification} of {@code jdk}). Any thread.
     */
    @SuppressWarnings("unchecked")
    static String renderKey(Map<String, Object> result) {
        Map<String, Object> keyed = new LinkedHashMap<String, Object>();
        Object jdk = result.get("jdk");
        keyed.put("jdk.specification", jdk instanceof Map ? ((Map<String, Object>) jdk).get("specification") : null);
        keyed.put("lookAndFeel", result.get("lookAndFeel"));
        keyed.put("antialiasing", result.get("antialiasing"));
        keyed.put("screenScale", result.get("screenScale"));
        keyed.put("probeHash", result.get("probeHash"));
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(Json.writeCanonical(keyed)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16)).append(Character.forDigit(d[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The toolkit's desktop font hints as sorted {@code key=value} strings, or an empty list. */
    static List<Object> desktopHints() {
        List<String> out = new ArrayList<String>();
        try {
            Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
            if (hints instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) hints).entrySet()) {
                    out.add(e.getKey() + "=" + e.getValue());
                }
            }
        } catch (RuntimeException ignored) {
            // no desktop properties: none
        }
        Collections.sort(out);
        return new ArrayList<Object>(out);
    }

    private static double screenScale() {
        try {
            if (GraphicsEnvironment.isHeadless()) {
                return 1.0;
            }
            return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                    .getDefaultConfiguration().getDefaultTransform().getScaleX();
        } catch (RuntimeException e) {
            return 1.0;
        }
    }

    /** The probe render: a fixed off-screen panel painted with {@code printAll} into an RGB image. EDT only. */
    static BufferedImage probe() {
        JPanel panel = new JPanel(null);
        panel.setSize(PROBE_WIDTH, PROBE_HEIGHT);
        JLabel plain = new JLabel("Plain label 0123");
        plain.setBounds(8, 4, 150, 20);
        JLabel bold = new JLabel("Bold label 4567");
        bold.setFont(bold.getFont().deriveFont(Font.BOLD));
        bold.setBounds(162, 4, 150, 20);
        JTextField field = new JTextField("Text field 89");
        field.setBounds(8, 28, 150, 24);
        DefaultTableColumnModel columns = new DefaultTableColumnModel();
        TableColumn first = new TableColumn(0, 75);
        first.setHeaderValue("Item");
        TableColumn second = new TableColumn(1, 75);
        second.setHeaderValue("Due");
        columns.addColumn(first);
        columns.addColumn(second);
        JTableHeader header = new JTableHeader(columns);
        header.setBounds(162, 28, 150, 24);
        JButton button = new JButton("Button");
        button.setBounds(8, 60, 150, 28);
        JComponent ellipse = new JComponent() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(new Color(31, 95, 168));
                    g2.fillOval(2, 2, getWidth() - 4, getHeight() - 4);
                } finally {
                    g2.dispose();
                }
            }
        };
        ellipse.setBounds(162, 60, 150, 52);
        panel.add(plain);
        panel.add(bold);
        panel.add(field);
        panel.add(header);
        panel.add(button);
        panel.add(ellipse);
        BufferedImage image = new BufferedImage(PROBE_WIDTH, PROBE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            Color back = panel.getBackground();
            g.setColor(back != null ? back : Color.WHITE);
            g.fillRect(0, 0, PROBE_WIDTH, PROBE_HEIGHT);
            panel.printAll(g);
        } finally {
            g.dispose();
        }
        return image;
    }
}
