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

import javax.swing.JTextField;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;

/**
 * The desk's single entry field: item codes, quantities, amounts, staff codes and passwords are all typed here
 * and submitted with Enter. While {@link #isMasked()} is true (the engine expects a password) the document holds one
 * {@code '*'} per typed character and the real text is kept aside, so {@link #getText()} shows only the mask;
 * {@link #value()} and {@link #takeValue()} return what was really typed. Named {@code "entry"}.
 *
 * <p>Event dispatch thread only, like every Swing component.
 */
public final class EntryField extends JTextField {

    /** Component name of the entry field. */
    public static final String NAME = "entry";

    private static final long serialVersionUID = 1L;

    private final StringBuilder real = new StringBuilder();
    private boolean masked;

    public EntryField(int columns) {
        super(columns);
        setName(NAME);
        ((AbstractDocument) getDocument()).setDocumentFilter(new MaskFilter());
    }

    /** @return true while typed characters are shown as {@code '*'} */
    public boolean isMasked() {
        return masked;
    }

    /** Switches masking on or off; a change clears the field so that nothing typed is revealed or mis-masked. */
    public void setMasked(boolean on) {
        if (on != masked) {
            masked = on;
            setText("");
        }
    }

    /** @return the text really typed (unmasked) */
    public String value() {
        return real.toString();
    }

    /** @return the text really typed, clearing the field */
    public String takeValue() {
        String v = real.toString();
        setText("");
        return v;
    }

    private String shown(String typed) {
        if (!masked || typed == null) {
            return typed;
        }
        StringBuilder sb = new StringBuilder(typed.length());
        for (int i = 0; i < typed.length(); i++) {
            sb.append('*');
        }
        return sb.toString();
    }

    private final class MaskFilter extends DocumentFilter {
        @Override
        public void insertString(FilterBypass fb, int offset, String text, AttributeSet attr)
            throws BadLocationException {
            if (text == null) {
                return;
            }
            real.insert(Math.min(offset, real.length()), text);
            fb.insertString(offset, shown(text), attr);
        }

        @Override
        public void remove(FilterBypass fb, int offset, int length) throws BadLocationException {
            int end = Math.min(offset + length, real.length());
            if (offset < end) {
                real.delete(offset, end);
            }
            fb.remove(offset, length);
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
            throws BadLocationException {
            int end = Math.min(offset + length, real.length());
            String t = text == null ? "" : text;
            if (offset <= real.length()) {
                real.replace(offset, Math.max(offset, end), t);
            }
            fb.replace(offset, length, shown(t), attrs);
        }
    }
}
