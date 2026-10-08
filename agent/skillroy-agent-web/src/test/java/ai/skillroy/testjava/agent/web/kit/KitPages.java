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

package ai.skillroy.testjava.agent.web.kit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The kit's own pages (phase4-contracts.md section 10.1), resources of the kit next to this class: {@code main.html}
 * (a title, a heading, a coloured block, a console line, an inline script that records whether the init script ran
 * before it, and an {@code iframe} whose {@code src} is a {@code data:} URL of {@code inner.html}; never
 * {@code srcdoc}, which BS8 showed does not load in the image) and {@code second.html} (no frame). A fixture loads
 * the HTML text however its technology does it, for example as a {@code data:} URL ({@link #dataUrl}).
 */
public final class KitPages {

    /** The main page's title. */
    public static final String MAIN_TITLE = "Kit main";
    /** The inner frame's title. */
    public static final String INNER_TITLE = "Kit inner";
    /** The inner frame's name. */
    public static final String INNER_NAME = "inner";
    /** The second page's title. */
    public static final String SECOND_TITLE = "Kit second";
    /** The pages' background colour, RGB (the corner of a bitmap). */
    public static final int BACKGROUND_RGB = (32 << 16) | (96 << 8) | 160;

    private KitPages() {
    }

    /** {@code main.html} with its iframe pointing at {@code inner.html} as a {@code data:} URL. */
    public static String main() {
        return resource("main.html").replace("{{INNER}}", dataUrl(inner()));
    }

    /** {@code inner.html}. */
    public static String inner() {
        return resource("inner.html");
    }

    /** {@code second.html}. */
    public static String second() {
        return resource("second.html");
    }

    /** {@code data:text/html;charset=utf-8,<percent-encoded HTML>} (spaces as {@code %20}, as BS8 built them). */
    public static String dataUrl(String html) {
        try {
            return "data:text/html;charset=utf-8," + URLEncoder.encode(html, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String resource(String name) {
        InputStream in = KitPages.class.getResourceAsStream(name);
        if (in == null) {
            throw new IllegalStateException("missing kit page " + name);
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read kit page " + name, e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // a class-path resource
            }
        }
    }
}
