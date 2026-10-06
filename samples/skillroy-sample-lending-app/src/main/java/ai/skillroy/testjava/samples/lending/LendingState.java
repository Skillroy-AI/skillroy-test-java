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

/**
 * The states of the desk's {@link Engine}. Each state accepts a fixed set of input sources
 * ({@link Engine#validInputs()}); an input from any other source is silently dropped. Some states have sub-steps
 * (a pending quantity, the supervisor sub-flow of a return) that keep the state but change the prompt and the valid
 * inputs. While the engine is busy or a modal prompt is open it accepts nothing and {@code validInputs()} is empty.
 *
 * <table border="1">
 *   <caption>Prompts and valid inputs</caption>
 *   <tr><th>State (sub-step)</th><th>Prompt</th><th>Valid inputs</th></tr>
 *   <tr><td>SIGNED_OFF</td><td>Enter staff code</td><td>Entry</td></tr>
 *   <tr><td>PASSWORD</td><td>Enter password</td><td>Entry, Key.SIGN_OFF</td></tr>
 *   <tr><td>IDLE</td><td>Scan or enter item</td><td>Barcode, Entry, Key.RETURN, Key.SIGN_OFF, Key.QTY</td></tr>
 *   <tr><td>LOAN</td><td>Scan or enter item, or DUE</td><td>Barcode, Entry, Key.DUE, Key.CANCEL, Key.QTY</td></tr>
 *   <tr><td>DEPOSIT</td><td>Deposit received</td><td>Entry, Key.DEPOSIT, Key.CANCEL</td></tr>
 *   <tr><td>RETURN, no lines yet</td><td>Return: scan or enter item</td>
 *       <td>Barcode, Entry, Key.RETURN, Key.CANCEL, Key.QTY</td></tr>
 *   <tr><td>RETURN, with lines</td><td>Return: scan or enter item</td>
 *       <td>Barcode, Entry, Key.DUE, Key.CANCEL, Key.QTY</td></tr>
 *   <tr><td>IDLE, LOAN or RETURN after Key.QTY without a value</td><td>Enter quantity</td>
 *       <td>Entry, Key.CANCEL</td></tr>
 *   <tr><td>RETURN, supervisor code</td><td>Enter supervisor code</td><td>Entry, Key.CANCEL</td></tr>
 *   <tr><td>RETURN, supervisor password</td><td>Enter supervisor password</td><td>Entry, Key.CANCEL</td></tr>
 *   <tr><td>LOCKED</td><td>Locked: enter password</td><td>Entry, Key.SIGN_OFF</td></tr>
 * </table>
 *
 * <p>With a quantity pending, the IDLE, LOAN and RETURN prompts are prefixed with {@code "Qty <n> - "}. In DEPOSIT,
 * after an amount was entered, the prompt is {@code "Deposit received <amount>: press DEPOSIT"}.
 */
public enum LendingState {
    /** Nobody is signed on; waits for a staff code. */
    SIGNED_OFF,
    /** A staff code was accepted; waits for that staff member's password. */
    PASSWORD,
    /** Signed on with no loan open. */
    IDLE,
    /** A loan with at least one line is open. */
    LOAN,
    /** DUE was pressed: the waiver rules are applied and the engine waits for the deposit to be received. */
    DEPOSIT,
    /** Return mode (toggled from IDLE): every line entered is a return line; holds the supervisor sub-flow. */
    RETURN,
    /** Locked after the idle timeout; the signed-on staff member's password unlocks it. */
    LOCKED
}
