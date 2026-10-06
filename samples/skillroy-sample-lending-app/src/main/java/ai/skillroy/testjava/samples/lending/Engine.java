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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The desk engine: one thread, named {@value #THREAD_NAME}, that runs the state machine described in
 * {@link LendingState}. It blocks on the {@link Inbox} while it waits for input, checks each input it takes against the
 * inputs the current state accepts (an input from any other source is silently dropped, logged at {@code FINE}
 * only), asks modal questions through the {@link PromptService} (blocking until answered), posts loans to the
 * database and prints slips.
 *
 * <p><b>Threading.</b> The engine thread is the only thread that changes the loan, the state and the database
 * (through {@link LendingDb#connection()}). Every accessor here may be called from any thread: it returns an immutable
 * snapshot published by the engine thread ({@link #status()}, {@link #currentLoan()}, {@link #lastLoan()}).
 * Listener callbacks run on the engine thread (see {@link EngineListener}).
 *
 * <p><b>Business rules.</b>
 * <ul>
 *   <li>Sign-on: the staff code must exist and be active, the password must match and the staff member needs
 *       {@code SIGN_ON}. Failures show a prompt with {@code OK} and return to SIGNED_OFF. A staff member flagged
 *       {@code auto_sign_on} is signed on by the code alone.</li>
 *   <li>Items: {@code Barcode} looks up the barcode; {@code Entry} looks up a barcode, then an item code. Unknown items
 *       prompt "Unknown item", inactive ones "Item not for loan". Scanning a counted item that is already on the
 *       loan adds to that line; a per-day item needs its number of days entered with QTY first and always gets a new
 *       line. An induction-required item asks the induction-check prompt ({@code Yes}/{@code No}) on a loan (not on a
 *       return). {@code QTY} then a number sets the quantity of the next item only.</li>
 *   <li>Returns: in RETURN mode every line is negative and needs {@code RETURN}. The return value (the absolute sum
 *       of the loan's return lines, the new one included) above the system maximum prompts "Return not allowed";
 *       above the staff member's limit it prompts "Supervisor required" ({@code OK}/{@code Cancel}) and, on OK,
 *       asks for a supervisor code and password. The supervisor must be another active staff member with
 *       {@code RETURN_OVERRIDE} and a limit covering the value; then the line is added and a
 *       {@code RETURN_OVERRIDE} authorisation is recorded. That authorisation covers later lines up to the
 *       supervisor's limit. Otherwise "Authorisation failed" and the line is not added.</li>
 *   <li>DUE computes the deposit due, applying the waiver rules ({@link WaiverEngine}; not on a return), and moves
 *       to DEPOSIT. The DEPOSIT key with an amount at least the amount due records the loan (no amount: the amount
 *       entered before, else exactly the amount due); a return hands its (negative) total back and gives no change.
 *       Recording writes {@code loan}, {@code loan_line}, {@code loan_waiver}, {@code loan_deposit} and
 *       {@code loan_authorisation} in one transaction, prints the slip and returns to IDLE.</li>
 *   <li>Idle lock: with a positive idle-lock time, waiting that long in IDLE, LOAN, RETURN or DEPOSIT (with no
 *       sub-step pending) locks the desk; the signed-on staff member's password unlocks it to the same state;
 *       SIGN_OFF from LOCKED abandons any open loan.</li>
 * </ul>
 */
public final class Engine {

    /** Name of the engine thread. */
    public static final String THREAD_NAME = "LendingEngine";
    /** Kind of the authorisation recorded for a return above the staff member's limit. */
    public static final String AUTH_RETURN_OVERRIDE = "RETURN_OVERRIDE";

    /** Button labels. */
    public static final String OK = "OK";
    public static final String CANCEL = "Cancel";
    public static final String YES = "Yes";
    public static final String NO = "No";

    /** Prompt ids; the texts are the matching {@code TEXT_*} constants. */
    public static final String PROMPT_UNKNOWN_STAFF = "unknown-staff";
    public static final String PROMPT_STAFF_INACTIVE = "staff-inactive";
    public static final String PROMPT_WRONG_PASSWORD = "wrong-password";
    public static final String PROMPT_SIGN_ON_NOT_ALLOWED = "sign-on-not-allowed";
    public static final String PROMPT_UNKNOWN_ITEM = "unknown-item";
    public static final String PROMPT_NOT_FOR_LOAN = "item-not-for-loan";
    public static final String PROMPT_DAYS_REQUIRED = "days-required";
    public static final String PROMPT_INVALID_QUANTITY = "invalid-quantity";
    public static final String PROMPT_INDUCTION_CHECK = "induction-check";
    public static final String PROMPT_RETURN_NOT_PERMITTED = "return-not-permitted";
    public static final String PROMPT_RETURN_NOT_ALLOWED = "return-not-allowed";
    public static final String PROMPT_SUPERVISOR_REQUIRED = "supervisor-required";
    public static final String PROMPT_AUTHORISATION_FAILED = "authorisation-failed";
    public static final String PROMPT_INVALID_AMOUNT = "invalid-amount";
    public static final String PROMPT_INSUFFICIENT_AMOUNT = "insufficient-amount";
    public static final String PROMPT_POSTING_FAILED = "posting-failed";

    public static final String TEXT_UNKNOWN_STAFF = "Unknown staff code";
    public static final String TEXT_STAFF_INACTIVE = "Staff member is not active";
    public static final String TEXT_WRONG_PASSWORD = "Wrong password";
    public static final String TEXT_SIGN_ON_NOT_ALLOWED = "Sign-on not allowed";
    public static final String TEXT_UNKNOWN_ITEM = "Unknown item";
    public static final String TEXT_NOT_FOR_LOAN = "Item not for loan";
    public static final String TEXT_DAYS_REQUIRED = "Per-day item: enter the days with QTY first";
    public static final String TEXT_INVALID_QUANTITY = "Invalid quantity";
    public static final String TEXT_INDUCTION_CHECK = "Has the borrower completed the safety induction?";
    public static final String TEXT_RETURN_NOT_PERMITTED = "Return not permitted";
    public static final String TEXT_RETURN_NOT_ALLOWED = "Return not allowed";
    public static final String TEXT_SUPERVISOR_REQUIRED = "Supervisor required";
    public static final String TEXT_AUTHORISATION_FAILED = "Authorisation failed";
    public static final String TEXT_INVALID_AMOUNT = "Invalid amount";
    public static final String TEXT_INSUFFICIENT_AMOUNT = "Deposit received is less than the amount due";
    public static final String TEXT_POSTING_FAILED = "Loan could not be recorded";

    /** Prompt lines of the states; see {@link LendingState}. */
    public static final String LINE_STAFF_CODE = "Enter staff code";
    public static final String LINE_PASSWORD = "Enter password";
    public static final String LINE_ITEM = "Scan or enter item";
    public static final String LINE_ITEM_OR_DUE = "Scan or enter item, or DUE";
    public static final String LINE_RETURN_ITEM = "Return: scan or enter item";
    public static final String LINE_QUANTITY = "Enter quantity";
    public static final String LINE_DEPOSIT = "Deposit received";
    public static final String LINE_SUPERVISOR_CODE = "Enter supervisor code";
    public static final String LINE_SUPERVISOR_PASSWORD = "Enter supervisor password";
    public static final String LINE_LOCKED = "Locked: enter password";

    private static final Logger LOG = Logger.getLogger(Engine.class.getName());
    private static final List<String> NO_INPUTS = Collections.emptyList();

    private enum Step { NONE, QUANTITY, SUPERVISOR_CODE, SUPERVISOR_PASSWORD }

    private final LendingDb db;
    private final Catalogue catalogue;
    private final AccessControl access;
    private final WaiverEngine waiverRules;
    private final PromptService prompts;
    private final SlipPrinter printer;
    private final Clock clock;
    private final long idleLockMillis;
    private final Inbox inbox = new Inbox();
    /** Deliberately a plain list: see {@link #addListener}. */
    private final List<EngineListener> listeners = new ArrayList<EngineListener>();
    private final PromptService.Listener promptForwarder = new PromptService.Listener() {
        @Override
        public void shown(Prompt prompt) {
            fire(l -> l.promptShown(prompt.id(), prompt.text(), prompt.buttons()));
        }

        @Override
        public void answered(Prompt prompt, String choice) {
            fire(l -> l.promptAnswered(prompt.id(), choice));
        }
    };

    private final Object lifecycle = new Object();
    private volatile Thread thread;
    private volatile boolean running;
    private volatile EngineStatus status;
    private volatile Loan currentLoan;
    private volatile Loan lastLoan;

    // Confined to the engine thread.
    private LendingState state = LendingState.SIGNED_OFF;
    private Step step = Step.NONE;
    private Staff staff;
    private Staff pendingStaff;
    private boolean returnMode;
    private LendingState lockedFrom;
    private BigDecimal pendingQty;
    private BigDecimal pendingDeposit;
    private List<LoanLine> pendingReturnLines;
    private BigDecimal pendingReturnValue;
    private String pendingSupervisor;
    private boolean authorised;
    private BigDecimal authorisedLimit;
    private Loan loan;

    /**
     * @param db the database; the engine writes through {@link LendingDb#connection()}
     * @param catalogue items and their deposits
     * @param access staff, permissions and limits
     * @param waiverRules the waiver rules
     * @param prompts where modal questions are asked
     * @param printer where slips are printed
     * @param clock business date and posting time
     * @param idleLockMillis idle time after which the desk locks; 0 for never
     */
    public Engine(LendingDb db, Catalogue catalogue, AccessControl access, WaiverEngine waiverRules,
                  PromptService prompts, SlipPrinter printer, Clock clock, long idleLockMillis) {
        this.db = Objects.requireNonNull(db, "db");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.access = Objects.requireNonNull(access, "access");
        this.waiverRules = Objects.requireNonNull(waiverRules, "waiverRules");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.printer = Objects.requireNonNull(printer, "printer");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (idleLockMillis < 0) {
            throw new IllegalArgumentException("idleLockMillis must not be negative");
        }
        this.idleLockMillis = idleLockMillis;
        this.status = new EngineStatus(LendingState.SIGNED_OFF, LINE_STAFF_CODE, NO_INPUTS, false, null, false, false,
            false);
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Starts the engine thread (non-daemon, named {@value #THREAD_NAME}). It publishes SIGNED_OFF and calls
     * {@code inputRequested} before it first blocks.
     *
     * @throws IllegalStateException if already started
     */
    public void start() {
        synchronized (lifecycle) {
            if (thread != null) {
                throw new IllegalStateException("engine already started");
            }
            running = true;
            prompts.addListener(promptForwarder);
            Thread t = new Thread(this::run, THREAD_NAME);
            t.setDaemon(false);
            thread = t;
            t.start();
        }
    }

    /** Stops the engine thread and waits up to 5 seconds for it to end. */
    public void stop() {
        stop(5000);
    }

    /**
     * Stops the engine thread: an open prompt is abandoned unanswered and queued inputs are discarded.
     *
     * @param timeoutMs how long to wait for the thread to end; zero or less asks it to stop without waiting
     * @return true if the thread has ended (false when called on the engine thread itself)
     */
    public boolean stop(long timeoutMs) {
        Thread t;
        synchronized (lifecycle) {
            running = false;
            t = thread;
        }
        if (t == null) {
            return true;
        }
        if (t == Thread.currentThread()) {
            return false;
        }
        t.interrupt();
        if (timeoutMs > 0) {
            // Thread.join(0) would wait for ever, so a non-positive timeout must not reach it.
            try {
                t.join(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return !t.isAlive();
    }

    /** @return true while the engine thread runs */
    public boolean isRunning() {
        Thread t = thread;
        return running && t != null && t.isAlive();
    }

    /** @return the engine thread, or null before {@link #start()} */
    public Thread thread() {
        return thread;
    }

    // ------------------------------------------------------------------ observation (any thread)

    /** @return where every input source posts; thread-safe */
    public Inbox inbox() {
        return inbox;
    }

    /** @return the latest published status; any thread */
    public EngineStatus status() {
        return status;
    }

    /** @return the current state */
    public LendingState state() {
        return status.state();
    }

    /** @return the current prompt line */
    public String prompt() {
        return status.prompt();
    }

    /** @return the input sources the engine accepts now; empty while busy or while a modal prompt is open */
    public List<String> validInputs() {
        return status.validInputs();
    }

    /** @return true if an input with this source would be accepted now (a check, not a promise: see {@link Inbox}) */
    public boolean isValid(LendingInput input) {
        return input != null && status.validInputs().contains(input.source());
    }

    /** @return true while the engine is blocked waiting for input (the moment to add or remove a listener) */
    public boolean isAwaitingInput() {
        return status.awaitingInput();
    }

    /** @return the signed-on staff member, or null */
    public Staff currentStaff() {
        return status.staff();
    }

    /** @return a snapshot of the open loan, or null if none is open */
    public Loan currentLoan() {
        return currentLoan;
    }

    /** @return the most recently posted loan, or null */
    public Loan lastLoan() {
        return lastLoan;
    }

    /** @return true in return mode (RETURN, and DEPOSIT of a return) */
    public boolean isReturnMode() {
        return status.returnMode();
    }

    /** @return true while LOCKED */
    public boolean isLocked() {
        return status.locked();
    }

    /**
     * Adds a listener. The list is a plain {@link ArrayList} that the engine thread iterates whenever it reports
     * something: add listeners before {@link #start()}, or from another thread only while the engine is blocked
     * waiting for input ({@link #isAwaitingInput()}), never from inside a callback.
     */
    public void addListener(EngineListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** Removes a listener; the same rule as {@link #addListener} applies. */
    public void removeListener(EngineListener listener) {
        listeners.remove(listener);
    }

    /** @return how many listeners are registered */
    public int listenerCount() {
        return listeners.size();
    }

    public long idleLockMillis() {
        return idleLockMillis;
    }

    public Clock clock() {
        return clock;
    }

    public LendingDb db() {
        return db;
    }

    public Catalogue catalogue() {
        return catalogue;
    }

    public AccessControl access() {
        return access;
    }

    public WaiverEngine waiverRules() {
        return waiverRules;
    }

    public PromptService prompts() {
        return prompts;
    }

    public SlipPrinter printer() {
        return printer;
    }

    // ------------------------------------------------------------------ engine thread

    private void run() {
        try {
            enterWait();
            while (running) {
                LendingInput input = nextInput();
                if (input == null) {
                    break;
                }
                publish(false);
                fire(l -> l.inputAccepted(input));
                try {
                    handle(input);
                } catch (RuntimeException e) {
                    LOG.log(Level.SEVERE, "input " + input.source() + " failed in state " + state, e);
                    step = Step.NONE;
                }
                enterWait();
            }
        } catch (InterruptedException e) {
            LOG.fine("engine interrupted; stopping");
        } finally {
            running = false;
            inbox.clear();
            prompts.removeListener(promptForwarder);
            status = new EngineStatus(state, promptLine(), NO_INPUTS, false, staff, returnMode, false, false);
        }
    }

    /** Waits for the next input the current state accepts; locks on idle timeout. Null when stopping. */
    private LendingInput nextInput() throws InterruptedException {
        while (running) {
            LendingInput input;
            if (idleLockApplies()) {
                input = inbox.poll(idleLockMillis);
                if (input == null) {
                    lockedFrom = state;
                    setState(LendingState.LOCKED);
                    enterWait();
                    continue;
                }
            } else {
                input = inbox.take();
            }
            if (status.validInputs().contains(input.source())) {
                return input;
            }
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine("dropped input " + input.source() + " in state " + state);
            }
        }
        return null;
    }

    private boolean idleLockApplies() {
        return idleLockMillis > 0 && step == Step.NONE && (state == LendingState.IDLE || state == LendingState.LOAN
            || state == LendingState.RETURN || state == LendingState.DEPOSIT);
    }

    private void enterWait() {
        publish(true);
        LendingState s = state;
        String line = status.prompt();
        fire(l -> l.inputRequested(s, line));
    }

    private void publish(boolean awaiting) {
        status = new EngineStatus(state, promptLine(), awaiting ? validInputsNow() : NO_INPUTS, awaiting, staff,
            returnMode, secretEntry(), true);
    }

    private void setState(LendingState to) {
        if (state == to) {
            publish(false);
            return;
        }
        LendingState from = state;
        state = to;
        publish(false);
        fire(l -> l.stateChanged(from, to));
    }

    private void fire(Consumer<EngineListener> event) {
        Object[] snapshot = listeners.toArray();
        for (Object o : snapshot) {
            if (o == null) {
                continue;
            }
            try {
                event.accept((EngineListener) o);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "engine listener failed", e);
            }
        }
    }

    private String ask(String id, String text, String... buttons) throws InterruptedException {
        publish(false);
        return prompts.ask(id, text, Arrays.asList(buttons));
    }

    private List<String> validInputsNow() {
        List<String> v = new ArrayList<String>();
        String due = KeyAction.DUE.source();
        String deposit = KeyAction.DEPOSIT.source();
        String returned = KeyAction.RETURN.source();
        String cancelKey = KeyAction.CANCEL.source();
        String signOff = KeyAction.SIGN_OFF.source();
        String qty = KeyAction.QTY.source();
        switch (state) {
            case SIGNED_OFF:
                v.add(LendingInput.ENTRY);
                break;
            case PASSWORD:
            case LOCKED:
                v.add(LendingInput.ENTRY);
                v.add(signOff);
                break;
            case DEPOSIT:
                v.add(LendingInput.ENTRY);
                v.add(deposit);
                v.add(cancelKey);
                break;
            default:
                if (step != Step.NONE) {
                    v.add(LendingInput.ENTRY);
                    v.add(cancelKey);
                    break;
                }
                boolean hasLines = loan != null && !loan.lines().isEmpty();
                v.add(LendingInput.BARCODE);
                v.add(LendingInput.ENTRY);
                if (state == LendingState.LOAN || (state == LendingState.RETURN && hasLines)) {
                    v.add(due);
                }
                if (state == LendingState.IDLE || (state == LendingState.RETURN && !hasLines)) {
                    v.add(returned);
                }
                if (state != LendingState.IDLE) {
                    v.add(cancelKey);
                }
                if (state == LendingState.IDLE) {
                    v.add(signOff);
                }
                v.add(qty);
                break;
        }
        return v;
    }

    private String promptLine() {
        switch (state) {
            case SIGNED_OFF:
                return LINE_STAFF_CODE;
            case PASSWORD:
                return LINE_PASSWORD;
            case LOCKED:
                return LINE_LOCKED;
            case DEPOSIT:
                return pendingDeposit == null ? LINE_DEPOSIT
                    : LINE_DEPOSIT + " " + Deposits.format(pendingDeposit) + ": press DEPOSIT";
            default:
                break;
        }
        switch (step) {
            case QUANTITY:
                return LINE_QUANTITY;
            case SUPERVISOR_CODE:
                return LINE_SUPERVISOR_CODE;
            case SUPERVISOR_PASSWORD:
                return LINE_SUPERVISOR_PASSWORD;
            default:
                break;
        }
        String base = state == LendingState.RETURN ? LINE_RETURN_ITEM
            : state == LendingState.LOAN ? LINE_ITEM_OR_DUE : LINE_ITEM;
        return pendingQty == null ? base : "Qty " + Deposits.formatQty(pendingQty) + " - " + base;
    }

    private boolean secretEntry() {
        return state == LendingState.PASSWORD || state == LendingState.LOCKED || step == Step.SUPERVISOR_PASSWORD;
    }

    // ------------------------------------------------------------------ input handling (engine thread)

    private void handle(LendingInput input) throws InterruptedException {
        KeyAction key = input.keyAction();
        String value = input.value() == null ? "" : input.value().trim();
        switch (state) {
            case SIGNED_OFF:
                onStaffCode(value);
                break;
            case PASSWORD:
                if (key == KeyAction.SIGN_OFF) {
                    pendingStaff = null;
                    setState(LendingState.SIGNED_OFF);
                } else {
                    onPassword(value);
                }
                break;
            case LOCKED:
                if (key == KeyAction.SIGN_OFF) {
                    signOff();
                } else {
                    onUnlock(value);
                }
                break;
            case DEPOSIT:
                onDeposit(key, value);
                break;
            default:
                onLending(input.source(), key, value);
                break;
        }
    }

    private void onStaffCode(String code) throws InterruptedException {
        if (code.isEmpty()) {
            return;
        }
        Staff s = access.findStaff(code);
        if (s == null) {
            ask(PROMPT_UNKNOWN_STAFF, TEXT_UNKNOWN_STAFF, OK);
            return;
        }
        if (!s.active()) {
            ask(PROMPT_STAFF_INACTIVE, TEXT_STAFF_INACTIVE, OK);
            return;
        }
        if (s.autoSignOn()) {
            if (!access.hasPermission(s.code(), AccessControl.SIGN_ON)) {
                ask(PROMPT_SIGN_ON_NOT_ALLOWED, TEXT_SIGN_ON_NOT_ALLOWED, OK);
                return;
            }
            signOn(s);
            return;
        }
        pendingStaff = s;
        setState(LendingState.PASSWORD);
    }

    private void onPassword(String password) throws InterruptedException {
        if (password.isEmpty()) {
            return;
        }
        Staff s = pendingStaff;
        pendingStaff = null;
        if (s == null) {
            setState(LendingState.SIGNED_OFF);
            return;
        }
        if (!access.passwordMatches(s.code(), password)) {
            ask(PROMPT_WRONG_PASSWORD, TEXT_WRONG_PASSWORD, OK);
            setState(LendingState.SIGNED_OFF);
            return;
        }
        if (!access.hasPermission(s.code(), AccessControl.SIGN_ON)) {
            ask(PROMPT_SIGN_ON_NOT_ALLOWED, TEXT_SIGN_ON_NOT_ALLOWED, OK);
            setState(LendingState.SIGNED_OFF);
            return;
        }
        signOn(s);
    }

    private void signOn(Staff s) {
        staff = s;
        resetLoan();
        setState(LendingState.IDLE);
    }

    private void signOff() {
        resetLoan();
        staff = null;
        lockedFrom = null;
        setState(LendingState.SIGNED_OFF);
    }

    private void onUnlock(String password) throws InterruptedException {
        if (password.isEmpty()) {
            return;
        }
        if (staff != null && access.passwordMatches(staff.code(), password)) {
            LendingState back = lockedFrom == null ? LendingState.IDLE : lockedFrom;
            lockedFrom = null;
            setState(back);
        } else {
            ask(PROMPT_WRONG_PASSWORD, TEXT_WRONG_PASSWORD, OK);
        }
    }

    private void onLending(String source, KeyAction key, String value) throws InterruptedException {
        switch (step) {
            case QUANTITY:
                if (key == KeyAction.CANCEL) {
                    cancelLoan();
                } else if (!value.isEmpty()) {
                    step = Step.NONE;
                    setQuantity(value);
                }
                return;
            case SUPERVISOR_CODE:
                if (key == KeyAction.CANCEL) {
                    cancelLoan();
                } else if (!value.isEmpty()) {
                    pendingSupervisor = value;
                    step = Step.SUPERVISOR_PASSWORD;
                }
                return;
            case SUPERVISOR_PASSWORD:
                if (key == KeyAction.CANCEL) {
                    cancelLoan();
                } else if (!value.isEmpty()) {
                    authorise(value);
                }
                return;
            default:
                break;
        }
        if (key == null) {
            if (!value.isEmpty()) {
                addItem(source, value);
            }
            return;
        }
        switch (key) {
            case QTY:
                if (value.isEmpty()) {
                    step = Step.QUANTITY;
                } else {
                    setQuantity(value);
                }
                break;
            case RETURN:
                if (state == LendingState.IDLE) {
                    pendingQty = null;
                    returnMode = true;
                    setState(LendingState.RETURN);
                } else {
                    cancelLoan();
                }
                break;
            case SIGN_OFF:
                signOff();
                break;
            case DUE:
                computeDue();
                break;
            case CANCEL:
                cancelLoan();
                break;
            default:
                break;
        }
    }

    private void setQuantity(String value) throws InterruptedException {
        BigDecimal q = Deposits.parseQuantity(value);
        if (q == null) {
            pendingQty = null;
            ask(PROMPT_INVALID_QUANTITY, TEXT_INVALID_QUANTITY, OK);
        } else {
            pendingQty = q;
        }
    }

    private void addItem(String source, String code) throws InterruptedException {
        BigDecimal qty = pendingQty;
        pendingQty = null;
        Item item = LendingInput.BARCODE.equals(source) ? catalogue.findByBarcode(code) : catalogue.lookup(code);
        if (item == null) {
            ask(PROMPT_UNKNOWN_ITEM, TEXT_UNKNOWN_ITEM, OK);
            return;
        }
        if (!item.active()) {
            ask(PROMPT_NOT_FOR_LOAN, TEXT_NOT_FOR_LOAN, OK);
            return;
        }
        if (item.perDay()) {
            if (qty == null) {
                ask(PROMPT_DAYS_REQUIRED, TEXT_DAYS_REQUIRED, OK);
                return;
            }
        } else if (qty == null) {
            qty = BigDecimal.ONE;
        } else if (!Deposits.isWhole(qty)) {
            ask(PROMPT_INVALID_QUANTITY, TEXT_INVALID_QUANTITY, OK);
            return;
        }
        if (!returnMode && item.inductionRequired()
            && !YES.equals(ask(PROMPT_INDUCTION_CHECK, TEXT_INDUCTION_CHECK, YES, NO))) {
            return;
        }
        Loan base = loan != null ? loan : Loan.open(staff.code(), returnMode);
        List<LoanLine> lines = merge(base.lines(), item, qty, returnMode);
        if (returnMode) {
            addReturnLines(base, lines);
        } else {
            commit(base.withLines(lines));
        }
    }

    private static List<LoanLine> merge(List<LoanLine> lines, Item item, BigDecimal qty, boolean returned) {
        List<LoanLine> result = new ArrayList<LoanLine>(lines);
        if (!item.perDay()) {
            for (int i = 0; i < result.size(); i++) {
                LoanLine line = result.get(i);
                if (line.code().equals(item.code()) && line.returned() == returned && !line.perDay()
                    && line.unitDeposit().compareTo(item.unitDeposit()) == 0) {
                    result.set(i, line.withQty(line.qty().add(qty)));
                    return result;
                }
            }
        }
        result.add(LoanLine.of(result.size() + 1, item, qty, returned));
        return result;
    }

    private void addReturnLines(Loan base, List<LoanLine> lines) throws InterruptedException {
        if (!access.hasPermission(staff.code(), AccessControl.RETURN)) {
            ask(PROMPT_RETURN_NOT_PERMITTED, TEXT_RETURN_NOT_PERMITTED, OK);
            return;
        }
        BigDecimal value = base.withLines(lines).returnValue();
        BigDecimal max = access.systemMaxReturn();
        if (max != null && value.compareTo(max) > 0) {
            ask(PROMPT_RETURN_NOT_ALLOWED, TEXT_RETURN_NOT_ALLOWED, OK);
            return;
        }
        BigDecimal limit = access.returnLimit(staff.code());
        boolean ownAuthority = limit == null || value.compareTo(limit) <= 0;
        boolean covered = authorised && (authorisedLimit == null || value.compareTo(authorisedLimit) <= 0);
        if (ownAuthority || covered) {
            commit(base.withLines(lines));
            return;
        }
        if (!OK.equals(ask(PROMPT_SUPERVISOR_REQUIRED, TEXT_SUPERVISOR_REQUIRED, OK, CANCEL))) {
            return;
        }
        pendingReturnLines = lines;
        pendingReturnValue = value;
        step = Step.SUPERVISOR_CODE;
    }

    private void authorise(String password) throws InterruptedException {
        String code = pendingSupervisor;
        List<LoanLine> lines = pendingReturnLines;
        BigDecimal value = pendingReturnValue;
        pendingSupervisor = null;
        pendingReturnLines = null;
        pendingReturnValue = null;
        step = Step.NONE;
        boolean ok = code != null && lines != null && value != null && !code.equals(staff.code())
            && access.passwordMatches(code, password) && access.canAuthoriseReturn(code, value);
        if (!ok) {
            ask(PROMPT_AUTHORISATION_FAILED, TEXT_AUTHORISATION_FAILED, OK);
            return;
        }
        Loan base = loan != null ? loan : Loan.open(staff.code(), true);
        authorised = true;
        authorisedLimit = access.returnLimit(code);
        commit(base.withLines(lines).withAuthorisation(new LoanAuthorisation(AUTH_RETURN_OVERRIDE, code)));
    }

    private void commit(Loan changed) {
        loan = changed;
        currentLoan = changed;
        if (state == LendingState.IDLE) {
            setState(LendingState.LOAN);
        }
    }

    private void computeDue() {
        List<LoanWaiver> waivers = returnMode ? Collections.<LoanWaiver>emptyList()
            : waiverRules.evaluate(loan.lines(), LocalDate.now(clock));
        loan = loan.withWaivers(waivers);
        currentLoan = loan;
        pendingDeposit = null;
        setState(LendingState.DEPOSIT);
    }

    private void cancelLoan() {
        resetLoan();
        setState(LendingState.IDLE);
    }

    private void resetLoan() {
        loan = null;
        currentLoan = null;
        returnMode = false;
        step = Step.NONE;
        pendingQty = null;
        pendingDeposit = null;
        pendingReturnLines = null;
        pendingReturnValue = null;
        pendingSupervisor = null;
        authorised = false;
        authorisedLimit = null;
    }

    private void onDeposit(KeyAction key, String value) throws InterruptedException {
        if (key == KeyAction.CANCEL) {
            cancelLoan();
            return;
        }
        if (key == null) {
            if (value.isEmpty()) {
                return;
            }
            BigDecimal amount = Deposits.parseMoney(value);
            if (amount == null) {
                ask(PROMPT_INVALID_AMOUNT, TEXT_INVALID_AMOUNT, OK);
            } else {
                pendingDeposit = amount;
            }
            return;
        }
        if (key == KeyAction.DEPOSIT) {
            BigDecimal amount = pendingDeposit;
            if (!value.isEmpty()) {
                amount = Deposits.parseMoney(value);
                if (amount == null) {
                    ask(PROMPT_INVALID_AMOUNT, TEXT_INVALID_AMOUNT, OK);
                    return;
                }
            }
            takeDeposit(amount);
        }
    }

    private void takeDeposit(BigDecimal amount) throws InterruptedException {
        BigDecimal total = loan.total();
        LoanDeposit deposit;
        BigDecimal change;
        if (total.signum() < 0) {
            deposit = new LoanDeposit(total);
            change = BigDecimal.ZERO;
        } else {
            BigDecimal given = amount == null ? total : amount;
            if (given.compareTo(total) < 0) {
                pendingDeposit = null;
                ask(PROMPT_INSUFFICIENT_AMOUNT, TEXT_INSUFFICIENT_AMOUNT, OK);
                return;
            }
            deposit = new LoanDeposit(given);
            change = given.subtract(total);
        }
        post(loan.withDeposit(deposit, change));
    }

    private void post(Loan withDeposit) throws InterruptedException {
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        long id;
        try {
            id = write(withDeposit, now);
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "posting failed", e);
            pendingDeposit = null;
            ask(PROMPT_POSTING_FAILED, TEXT_POSTING_FAILED, OK);
            return;
        }
        Loan posted = withDeposit.posted(id, now);
        lastLoan = posted;
        resetLoan();
        fire(l -> l.loanPosted(id));
        Slip slip = null;
        try {
            slip = printer.print(posted);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "printing the slip of loan " + id + " failed", e);
        }
        if (slip != null) {
            List<String> lines = slip.lines();
            fire(l -> l.slipPrinted(id, lines));
        }
        setState(LendingState.IDLE);
    }

    private long write(Loan s, LocalDateTime now) throws SQLException {
        Connection c = db.connection();
        try {
            long id;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("VALUES NEXT VALUE FOR " + LendingSchema.LOAN_ID_SEQUENCE)) {
                rs.next();
                id = rs.getLong(1);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO loan (id, staff_code, posted_at, total) VALUES (?, ?, ?, ?)")) {
                ps.setLong(1, id);
                ps.setString(2, s.staffCode());
                ps.setTimestamp(3, Timestamp.valueOf(now));
                ps.setBigDecimal(4, s.total());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO loan_line (loan_id, line_no, item_code, qty,"
                    + " unit_deposit, amount, returned) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                for (LoanLine line : s.lines()) {
                    ps.setLong(1, id);
                    ps.setInt(2, line.lineNo());
                    ps.setString(3, line.code());
                    ps.setBigDecimal(4, line.qty());
                    ps.setBigDecimal(5, line.unitDeposit());
                    ps.setBigDecimal(6, line.amount());
                    ps.setBoolean(7, line.returned());
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO loan_waiver (loan_id, rule_code, amount) VALUES (?, ?, ?)")) {
                for (LoanWaiver d : s.waivers()) {
                    ps.setLong(1, id);
                    ps.setString(2, d.ruleCode());
                    ps.setBigDecimal(3, d.amount());
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO loan_deposit (loan_id, amount) VALUES (?, ?)")) {
                for (LoanDeposit d : s.deposits()) {
                    ps.setLong(1, id);
                    ps.setBigDecimal(2, d.amount());
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO loan_authorisation (loan_id, kind, staff_code) VALUES (?, ?, ?)")) {
                for (LoanAuthorisation a : s.authorisations()) {
                    ps.setLong(1, id);
                    ps.setString(2, a.kind());
                    ps.setString(3, a.staffCode());
                    ps.executeUpdate();
                }
            }
            c.commit();
            return id;
        } catch (SQLException e) {
            try {
                c.rollback();
            } catch (SQLException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw e;
        }
    }
}
