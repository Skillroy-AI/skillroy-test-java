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

import java.util.Objects;

/**
 * The desk's barcode scanner driver: where a real scanner hands a raw frame over. It decodes the frame the scanner
 * sends ({@code STX} 0x02, 1 to 64 printable ASCII characters, {@code CR} 0x0D) into a {@link LendingInput#BARCODE}
 * input and posts it to the {@link Inbox}, the same way every input source does. A training desk has no driver:
 * {@link LendingApp#scannerDriver()} is null unless the desk was launched with
 * {@value LendingOptions#PROP_SCANNER_DRIVER}{@code =true} (phase8-contracts.md 12.1, 7.6).
 *
 * <p>Stateless apart from the inbox; thread-safe, never blocks.
 */
public final class ScannerDriver {

    /** The byte that starts a frame. */
    public static final byte STX = 0x02;
    /** The byte that ends a frame. */
    public static final byte CR = 0x0D;
    /** The longest code a frame carries. */
    public static final int MAX_CODE = 64;

    private final Inbox inbox;

    ScannerDriver(Inbox inbox) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
    }

    /**
     * The barcode input a frame carries, or null for a frame that is not {@code STX}, 1 to {@value #MAX_CODE}
     * printable ASCII characters (0x20 to 0x7E), {@code CR}.
     */
    public static LendingInput decode(byte[] frame) {
        if (frame == null || frame.length < 3 || frame.length > MAX_CODE + 2 || frame[0] != STX
                || frame[frame.length - 1] != CR) {
            return null;
        }
        StringBuilder code = new StringBuilder(frame.length - 2);
        for (int i = 1; i < frame.length - 1; i++) {
            int b = frame[i] & 0xFF;
            if (b < 0x20 || b > 0x7E) {
                return null;
            }
            code.append((char) b);
        }
        return LendingInput.barcode(code.toString());
    }

    /**
     * The frame a scanner sends for {@code code}, or null when no frame can carry it (empty, longer than
     * {@value #MAX_CODE}, or holding a character outside printable ASCII).
     */
    public static byte[] frame(String code) {
        if (code == null || code.isEmpty() || code.length() > MAX_CODE) {
            return null;
        }
        byte[] frame = new byte[code.length() + 2];
        frame[0] = STX;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return null;
            }
            frame[i + 1] = (byte) c;
        }
        frame[frame.length - 1] = CR;
        return frame;
    }

    /**
     * Posts a decoded input to the desk's inbox, as the driver does for every frame it reads.
     *
     * @return false if the input is null or the inbox is full
     */
    public boolean post(LendingInput input) {
        return inbox.offer(input);
    }
}
