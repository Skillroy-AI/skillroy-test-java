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

package ai.skillroy.testjava.samples.lending.agent;

import ai.skillroy.testjava.agent.api.spi.InputVeto;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JPanel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The desk's input veto without a display (phase8-contracts.md 12.2, 13.1 row 5): the "Desk tools" window's close
 * panel is refused on every route and {@code ANY} with the module's text; a component inside it is allowed by the veto
 * itself (the agent's ancestor rule refuses it through the panel); anything else is allowed.
 */
class LendingInputVetoTest {

    private static final List<String> ROUTES = Arrays.asList(InputVeto.CLICK_ACTION, InputVeto.CLICK_COMPONENT,
            InputVeto.CLICK_DISPATCH, InputVeto.TYPE_SET, InputVeto.TYPE_DISPATCH, InputVeto.KEY,
            InputVeto.POINTER_ROUTED, InputVeto.POINTER_ROBOT, InputVeto.DISMISS_BUTTON, InputVeto.DISMISS_CLOSE,
            InputVeto.ANY);

    @Test
    void theClosePanelIsRefusedOnEveryRouteAndTheRestIsAllowed() {
        LendingInputVeto veto = new LendingInputVeto();
        JPanel closeMenu = new JPanel();
        closeMenu.setName("closeMenu");
        JButton closeDesk = new JButton("Close the desk");
        closeDesk.setName("closeDesk");
        closeMenu.add(closeDesk);
        JButton printTestSlip = new JButton("Print test slip");
        printTestSlip.setName("printTestSlip");
        JPanel unnamed = new JPanel();
        for (String route : ROUTES) {
            assertEquals("closes the desk (the application would end)", veto.refuse(closeMenu, route), route);
            assertNull(veto.refuse(closeDesk, route), route + ": the ancestor rule does the rest");
            assertNull(veto.refuse(printTestSlip, route), route);
            assertNull(veto.refuse(unnamed, route), route);
        }
        assertNull(veto.refuse(null, InputVeto.ANY));
    }
}
