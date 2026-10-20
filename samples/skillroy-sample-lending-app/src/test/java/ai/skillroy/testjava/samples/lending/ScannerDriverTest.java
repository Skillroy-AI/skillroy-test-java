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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/** The desk's scanner driver and its launch property (phase8-contracts.md 12.1, 13.1 row 34). */
class ScannerDriverTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Test
    void aFrameIsDecodedIntoABarcodeInput() {
        LendingInput input = ScannerDriver.decode(bytes(0x02, '4', '0', '0', '5', 0x0D));
        assertNotNull(input);
        assertEquals(LendingInput.BARCODE, input.source());
        assertEquals("4005", input.value());
        assertEquals(" a~", ScannerDriver.decode(bytes(0x02, ' ', 'a', '~', 0x0D)).value(), "printable ASCII only");
        assertArrayEquals(bytes(0x02, 'T', '1', 0x0D), ScannerDriver.frame("T1"));
        char[] max = new char[ScannerDriver.MAX_CODE];
        Arrays.fill(max, '7');
        assertEquals(new String(max), ScannerDriver.decode(ScannerDriver.frame(new String(max))).value());
    }

    @Test
    void anythingElseIsRefused() {
        assertNull(ScannerDriver.decode(null));
        assertNull(ScannerDriver.decode(bytes()));
        assertNull(ScannerDriver.decode(bytes(0x02, 0x0D)), "an empty code");
        assertNull(ScannerDriver.decode(bytes('4', '0', 0x0D)), "no STX");
        assertNull(ScannerDriver.decode(bytes(0x02, '4', '0')), "no CR");
        assertNull(ScannerDriver.decode(bytes(0x02, '4', 0x0A, '0', 0x0D)), "a control character");
        assertNull(ScannerDriver.decode(bytes(0x02, '4', 0xE9, 0x0D)), "not ASCII");
        assertNull(ScannerDriver.decode(bytes(0x02, '4', 0x7F, 0x0D)), "DEL");
        char[] over = new char[ScannerDriver.MAX_CODE + 1];
        Arrays.fill(over, '7');
        byte[] long1 = new byte[over.length + 2];
        long1[0] = 0x02;
        for (int i = 0; i < over.length; i++) {
            long1[i + 1] = '7';
        }
        long1[long1.length - 1] = 0x0D;
        assertNull(ScannerDriver.decode(long1), "65 characters");
        assertNull(ScannerDriver.frame(new String(over)));
        assertNull(ScannerDriver.frame("4\t5"));
        assertNull(ScannerDriver.frame(""));
        assertNull(ScannerDriver.frame("café"));
    }

    @Test
    void theDriverRunsOnlyWhenTheLaunchPropertySaysSo() throws Exception {
        Properties p = new Properties();
        assertFalse(LendingOptions.fromProperties(p).scannerDriver(), "a training desk has no driver");
        p.setProperty(LendingOptions.PROP_SCANNER_DRIVER, " TRUE ");
        assertTrue(LendingOptions.fromProperties(p).scannerDriver());
        p.setProperty(LendingOptions.PROP_SCANNER_DRIVER, "false");
        assertFalse(LendingOptions.fromProperties(p).scannerDriver());
        p.setProperty(LendingOptions.PROP_SCANNER_DRIVER, "yes");
        assertThrows(IllegalArgumentException.class, () -> LendingOptions.fromProperties(p));

        LendingApp plain = LendingApp.launch(LendingOptions.defaults().ui(false).dbName("scanner_off"));
        try {
            assertNull(plain.scannerDriver());
        } finally {
            plain.shutdown();
        }
        LendingApp app = LendingApp.launch(LendingOptions.defaults().ui(false).dbName("scanner_on")
                .scannerDriver(true));
        try {
            ScannerDriver driver = app.scannerDriver();
            assertNotNull(driver);
            assertTrue(driver.post(ScannerDriver.decode(ScannerDriver.frame("4005"))), "posted to the inbox");
            assertFalse(driver.post(null));
        } finally {
            app.shutdown();
        }
    }
}
