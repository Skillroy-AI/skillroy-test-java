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

import ai.skillroy.testjava.agent.api.spi.AppStateProvider;
import ai.skillroy.testjava.agent.api.spi.DataSourceProvider;
import ai.skillroy.testjava.agent.api.spi.DeviceAdapter;
import ai.skillroy.testjava.agent.api.spi.MaskPolicy;
import ai.skillroy.testjava.agent.data.DataModule;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.TopicFilter;
import ai.skillroy.testjava.samples.lending.AccessControl;
import ai.skillroy.testjava.samples.lending.Deposits;
import ai.skillroy.testjava.samples.lending.Item;
import ai.skillroy.testjava.samples.lending.LendingDb;
import ai.skillroy.testjava.samples.lending.Seed;
import ai.skillroy.testjava.samples.lending.Staff;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.samples.lending.agent.FakeAgentContext.args;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lending primitives, events and SPIs against a running headless desk (seed A). */
class LendingPrimitivesTest {

    private LendingFixture lending;

    @BeforeEach
    void start() throws Exception {
        lending = LendingFixture.start();
    }

    @AfterEach
    void stop() {
        lending.close();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    @Test
    void aBasicLoanThroughLendingInputPublishesTheDomainEvents() throws Exception {
        long start = lending.ctx.events.lastSeq();
        Map<String, Object> first = map(lending.ctx.call("lending.input", args("source", "Entry", "value", "100")));
        assertEquals(Boolean.TRUE, first.get("accepted"));
        assertEquals(Boolean.TRUE, first.get("acked"));
        assertTrue(((Long) first.get("ackMs")).longValue() >= 0);
        Event acked = lending.ctx.events.next("input.acked", start);
        assertEquals(Collections.singletonMap("source", "Entry"), acked.payload(), "the value is never published");
        Event password = lending.ctx.events.next("input.requested", start);
        assertEquals("PASSWORD", password.payload().get("state"));
        assertEquals("Enter password", password.payload().get("prompt"));
        assertEquals(Arrays.asList("Entry", "Key.SIGN_OFF"), password.payload().get("validInputs"));
        Event changed = lending.ctx.events.next("state.changed", start);
        assertEquals("SIGNED_OFF", changed.payload().get("from"));
        assertEquals("PASSWORD", changed.payload().get("to"));

        assertEquals("IDLE", lending.send("Entry", "1111").payload().get("state"));
        assertEquals("LOAN", lending.send("Barcode", "5000000000017").payload().get("state"));
        assertEquals("LOAN", lending.send("Barcode", "5000000000017").payload().get("state"));
        Map<String, Object> open = map(lending.ctx.call("lending.loan", args()));
        assertEquals(Boolean.TRUE, open.get("open"));
        assertNull(open.get("id"));
        assertEquals("7.00", open.get("total"));
        Map<String, Object> line = map(list(open.get("lines")).get(0));
        assertEquals("YRD-001", line.get("code"));
        assertEquals("2", line.get("qty"));
        assertEquals("3.50", line.get("unitDeposit"));
        assertEquals("7.00", line.get("amount"));

        assertEquals("DEPOSIT", lending.send("Key.DUE", null).payload().get("state"));
        long beforeDeposit = lending.ctx.events.lastSeq();
        assertEquals("IDLE", lending.send("Key.DEPOSIT", "10.00").payload().get("state"));
        Event posted = lending.ctx.events.next("loan.posted", beforeDeposit);
        assertEquals(Long.valueOf(1001), posted.payload().get("loanId"));
        assertEquals("7.00", posted.payload().get("total"));
        assertEquals(Long.valueOf(1001),
                lending.ctx.events.next("slip.printed", beforeDeposit).payload().get("loanId"));

        Map<String, Object> last = map(lending.ctx.call("lending.loan", args()));
        assertEquals(Long.valueOf(1001), last.get("id"), "falls back to the last posted loan");
        assertEquals(Boolean.FALSE, last.get("open"));
        assertEquals("7.00", last.get("total"));
        assertEquals("3.00", last.get("change"));
        assertEquals(Collections.singletonList(args("amount", "10.00")), last.get("deposits"));
        assertEquals(Collections.emptyList(), last.get("authorisations"));
        assertNotNull(last.get("postedAt"));

        Map<String, Object> slips = map(lending.ctx.call("lending.slip", args()));
        Map<String, Object> slip = map(list(slips.get("slips")).get(0));
        assertEquals(Long.valueOf(1001), slip.get("loanId"));
        assertTrue(((String) slip.get("text")).contains("DEPOSIT DUE"), (String) slip.get("text"));

        Map<String, Object> state = map(lending.ctx.call("lending.state", args()));
        assertEquals("IDLE", state.get("name"));
        assertEquals(args("code", "100", "name", "Alice Archer"), state.get("staff"));
        assertEquals(Boolean.FALSE, state.get("returnMode"));
        assertEquals(Boolean.FALSE, state.get("locked"));
        assertEquals(Boolean.TRUE, state.get("awaitingInput"));
    }

    @Test
    void anInputTheStateDoesNotAcceptIsRejectedWithTheStateAndItsValidInputs() throws Exception {
        long before = lending.ctx.events.lastSeq();
        RpcException e = lending.ctx.fails("lending.input", args("source", "Key.DUE"));
        assertEquals(ErrorCodes.INPUT_REJECTED, e.error().code());
        assertEquals("SIGNED_OFF", e.error().data().get("state"));
        assertEquals(Collections.singletonList("Entry"), e.error().data().get("validInputs"));
        assertEquals(0, lending.app.inbox().size(), "nothing was queued");
        assertEquals(before, lending.ctx.events.lastSeq(), "nothing was published");

        RpcException bad = lending.ctx.fails("lending.input", args("source", "Entry", "ackTimeoutMs", -1L));
        assertEquals(ErrorCodes.INVALID_PARAMS, bad.error().code());
        assertEquals("ackTimeoutMs", bad.error().data().get("param"));
        assertEquals(ErrorCodes.INVALID_PARAMS, lending.ctx.fails("lending.input", args()).error().code());
    }

    @Test
    void aPromptIsPublishedReadAndAnswered() throws Exception {
        lending.signOn();
        Event waiting = lending.send("Barcode", "0000000000000");
        assertEquals("prompt.waiting", waiting.topic());
        assertEquals("unknown-item", waiting.payload().get("id"));
        assertEquals("Unknown item", waiting.payload().get("text"));
        assertEquals(Collections.singletonList("OK"), waiting.payload().get("buttons"));
        assertEquals(args("id", "unknown-item", "text", "Unknown item", "buttons", Collections.singletonList("OK")),
                lending.ctx.call("lending.prompt", args()));

        RpcException busy = lending.ctx.fails("lending.input", args("source", "Barcode", "value", "5000000000017"));
        assertEquals(ErrorCodes.INPUT_REJECTED, busy.error().code(), "nothing is accepted while a prompt is open");
        assertEquals(Collections.emptyList(), busy.error().data().get("validInputs"));
        assertEquals("unknown-item", map(busy.error().data().get("modalPrompt")).get("id"));

        RpcException wrong = lending.ctx.fails("lending.prompt.answer", args("choice", "Cancel"));
        assertEquals(ErrorCodes.INVALID_PARAMS, wrong.error().code());
        assertEquals(Collections.singletonList("OK"), wrong.error().data().get("buttons"));
        RpcException otherId = lending.ctx.fails("lending.prompt.answer",
                args("choice", "OK", "id", "induction-check"));
        assertEquals(ErrorCodes.NOT_FOUND, otherId.error().code());

        long before = lending.ctx.events.lastSeq();
        assertEquals(args("answered", Boolean.TRUE, "id", "unknown-item", "choice", "OK", "listening", Boolean.TRUE),
                lending.ctx.call("lending.prompt.answer", args("choice", "ok")));
        Event answered = lending.ctx.events.next("prompt.answered", before);
        assertEquals(args("id", "unknown-item", "choice", "OK"), answered.payload());
        assertEquals("IDLE", lending.ctx.events.next("input.requested", before).payload().get("state"));
        assertNull(lending.ctx.call("lending.prompt", args()));
        RpcException none = lending.ctx.fails("lending.prompt.answer", args("choice", "OK"));
        assertEquals(ErrorCodes.NOT_FOUND, none.error().code());
    }

    @Test
    void theLoanHasAnEmptyShapeBeforeTheFirstLoan() throws Exception {
        Map<String, Object> loan = map(lending.ctx.call("lending.loan", args()));
        assertNull(loan.get("id"));
        assertEquals(Boolean.FALSE, loan.get("open"));
        assertEquals(Collections.emptyList(), loan.get("lines"));
        assertEquals("0.00", loan.get("total"));
        assertEquals(Collections.emptyList(), map(lending.ctx.call("lending.slip", args())).get("slips"));
    }

    @Test
    void theAccessCheckUsesTheApplicationsOwnRules() throws Exception {
        assertEquals(args("allowed", Boolean.FALSE, "limit", "20.00", "reason", "amount exceeds the limit 20.00",
                "autoSignOn", Boolean.FALSE), lending.ctx.call("lending.access.check",
                        args("staff", "100", "permission", "RETURN", "amount", "25.00")));
        assertEquals(args("allowed", Boolean.TRUE, "limit", null, "reason", "permitted", "autoSignOn", Boolean.FALSE),
                lending.ctx.call("lending.access.check", args("staff", "300", "permission", "RETURN", "amount", 100L)));
        assertEquals(args("allowed", Boolean.FALSE, "limit", null, "reason", "missing permission RETURN",
                "autoSignOn", Boolean.FALSE),
                lending.ctx.call("lending.access.check", args("staff", "101", "permission", "RETURN")));
        assertEquals(args("allowed", Boolean.TRUE, "limit", null, "reason", "permitted", "autoSignOn", Boolean.TRUE),
                lending.ctx.call("lending.access.check", args("staff", "103", "permission", "SIGN_ON")));
        assertEquals(args("allowed", Boolean.FALSE, "limit", null, "reason", "unknown staff code", "autoSignOn",
                Boolean.FALSE), lending.ctx.call("lending.access.check", args("staff", "999", "permission",
                        "SIGN_ON")));
        RpcException bad = lending.ctx.fails("lending.access.check", args("staff", "100", "permission", "RETURN",
                "amount", "lots"));
        assertEquals(ErrorCodes.INVALID_PARAMS, bad.error().code());
        assertEquals("amount", bad.error().data().get("param"));
    }

    /**
     * {@code autoSignOn} is what the engine reads at sign-on (contract Q12): for every staff member it is the
     * application's own flag of an active member (false for an inactive one, whom the desk does not sign on at all),
     * and the desk asks for a password exactly when it is false.
     */
    @Test
    void autoSignOnSaysWhetherTheDeskAsksForAPassword() throws Exception {
        for (Staff staff : lending.app.access().staff()) {
            Map<String, Object> answer = map(lending.ctx.call("lending.access.check",
                    args("staff", staff.code(), "permission", "SIGN_ON")));
            assertEquals(Boolean.valueOf(staff.active() && staff.autoSignOn()), answer.get("autoSignOn"),
                    staff.code());
        }
        Event asked = lending.send("Entry", "100");
        assertEquals("PASSWORD", asked.payload().get("state"), "autoSignOn false: the desk asks for a password");
        assertEquals("SIGNED_OFF", lending.send("Key.SIGN_OFF", null).payload().get("state"));
        Event signedOn = lending.send("Entry", "103");
        assertEquals("IDLE", signedOn.payload().get("state"), "autoSignOn true: signed on with the code alone");
        assertEquals(args("code", "103", "name", "Fay Fisher"),
                map(lending.ctx.call("lending.state", args())).get("staff"));
    }

    /**
     * Contract 13.2: {@code autoSignOn} is false for an inactive staff member, even one whose own flag says the desk
     * signs them on without a password. No seed has such a member, so the test database makes one: staff 103 (auto
     * sign-on) is set inactive. The seeded, active 103 and an unknown code are the controls.
     */
    @Test
    void autoSignOnIsFalseForAnInactiveStaffMember() throws Exception {
        assertEquals(Boolean.TRUE, LendingPrimitives.accessCheck(lending.app.access(), "103", "SIGN_ON", null)
                .get("autoSignOn"), "the seeded 103 is active and signs on without a password");
        LendingDb db = LendingDb.create("lending_agent_inactive_auto", Seed.A, LocalDate.of(2026, 10, 1));
        try {
            Statement st = db.connection().createStatement();
            assertEquals(1, st.executeUpdate("UPDATE staff SET active = FALSE WHERE code = '103'"));
            st.close();
            AccessControl access = AccessControl.load(db.connection());
            Staff member = access.findStaff("103");
            assertNotNull(member);
            assertFalse(member.active(), "the test's premise: inactive");
            assertTrue(member.autoSignOn(), "the test's premise: its own flag says auto sign-on");
            Map<String, Object> answer = LendingPrimitives.accessCheck(access, "103", "SIGN_ON", null);
            assertEquals(Boolean.FALSE, answer.get("autoSignOn"), String.valueOf(answer));
            assertEquals(Boolean.FALSE, answer.get("allowed"), "the desk does not sign an inactive member on");
            assertEquals(Boolean.FALSE, LendingPrimitives.accessCheck(access, "999", "SIGN_ON", null)
                    .get("autoSignOn"), "unknown");
        } finally {
            db.close();
        }
    }

    @Test
    void theDepositLookupIsTheDesksOwn() throws Exception {
        long before = lending.ctx.events.lastSeq();
        assertEquals(args("found", Boolean.TRUE, "code", "PWR-001", "barcode", "5000000000093", "title",
                "Cordless drill", "deposit", "34.99", "active", Boolean.TRUE, "perDay", Boolean.FALSE,
                "inductionRequired", Boolean.FALSE),
                lending.ctx.call("lending.deposit", args("barcode", "5000000000093")));
        assertEquals(Boolean.TRUE, map(lending.ctx.call("lending.deposit", args("code", "cln-001"))).get("perDay"),
                "codes ignore case, as the desk's lookup does");
        assertEquals(Boolean.TRUE, map(lending.ctx.call("lending.deposit", args("code", "LAD-001")))
                .get("inductionRequired"));
        assertEquals(Boolean.FALSE, map(lending.ctx.call("lending.deposit", args("code", "TIL-001"))).get("active"),
                "a retired item is found and reported as inactive");
        for (Item item : lending.app.catalogue().items()) {
            Map<String, Object> expected = args("found", Boolean.TRUE, "code", item.code(), "barcode",
                    item.barcode(), "title", item.title(), "deposit", Deposits.format(item.unitDeposit()), "active",
                    Boolean.valueOf(item.active()), "perDay", Boolean.valueOf(item.perDay()), "inductionRequired",
                    Boolean.valueOf(item.inductionRequired()));
            assertEquals(expected, lending.ctx.call("lending.deposit", args("code", item.code())), item.code());
            if (item.barcode() != null) {
                assertEquals(expected, lending.ctx.call("lending.deposit", args("barcode", item.barcode())),
                        item.barcode());
            }
        }
        assertEquals(args("found", Boolean.FALSE), lending.ctx.call("lending.deposit", args("barcode", "0000")));
        assertEquals(args("found", Boolean.FALSE), lending.ctx.call("lending.deposit", args("barcode", "PWR-001")),
                "a barcode is matched exactly, as a scan is: an item code is not a barcode");
        assertEquals(args("found", Boolean.FALSE), lending.ctx.call("lending.deposit", args("code", "NOPE")));
        assertEquals("PWR-001", map(lending.ctx.call("lending.deposit", args("barcode", null, "code", "PWR-001")))
                .get("code"), "a null parameter counts as absent");

        RpcException both = lending.ctx.fails("lending.deposit", args("barcode", "5000000000093", "code", "PWR-001"));
        assertEquals(ErrorCodes.INVALID_PARAMS, both.error().code());
        assertEquals("code", both.error().data().get("param"));
        RpcException neither = lending.ctx.fails("lending.deposit", args());
        assertEquals(ErrorCodes.INVALID_PARAMS, neither.error().code());
        assertEquals("barcode", neither.error().data().get("param"));
        assertEquals(before, lending.ctx.events.lastSeq(), "a lookup publishes nothing");
        assertEquals("SIGNED_OFF", map(lending.ctx.call("lending.state", args())).get("name"));
    }

    @Test
    void theLimitsAreTheMaximumTheDeskLoadedAndEnforces() throws Exception {
        assertEquals(args("maxReturn", "500.00"), lending.ctx.call("lending.limits", args()));
        assertEquals(Deposits.format(lending.app.access().systemMaxReturn()),
                map(lending.ctx.call("lending.limits", args())).get("maxReturn"));
        LendingDb db = LendingDb.create("lending_agent_limits", Seed.A, LocalDate.of(2026, 10, 1));
        try {
            Statement st = db.connection().createStatement();
            st.executeUpdate("DELETE FROM desk_setting WHERE name = 'max_return'");
            st.close();
            AccessControl access = AccessControl.load(db.connection());
            assertNull(access.systemMaxReturn());
            assertEquals(args("maxReturn", null), LendingPrimitives.limits(access), "no maximum configured: null");
        } finally {
            db.close();
        }
    }

    @Test
    void thePasswordColumnOfTheLendingSourceIsMasked() throws Exception {
        List<MaskPolicy> masks = lending.ctx.spi.all(MaskPolicy.class);
        assertEquals(1, masks.size());
        MaskPolicy mask = masks.get(0);
        for (String column : Arrays.asList("password", "PASSWORD", "Password")) {
            assertEquals("<redacted>", mask.mask("lending", column, "1111"), column);
        }
        assertEquals("<redacted>", mask.mask("lending", "PASSWORD", Long.valueOf(1111)), "whatever the type");
        assertNull(mask.mask("lending", "PASSWORD", null), "no secret in a null");
        assertEquals("1111", mask.mask("other", "PASSWORD", "1111"), "another source is not the desk's");
        assertEquals("1111", mask.mask("LENDING", "PASSWORD", "1111"), "source ids are exact");
        for (String column : Arrays.asList("NAME", "PASSWORD_HINT", "PW", "c2", "")) {
            assertEquals("Alice", mask.mask("lending", column, "Alice"), column);
        }
        assertEquals("1111", mask.mask("lending", null, "1111"));
    }

    /** The real {@code data.query} path: no row it returns from the desk's database carries a staff password. */
    @Test
    void dataQueryNeverReturnsAStaffPassword() throws Exception {
        List<String> passwords = new ArrayList<String>();
        Connection c = lending.app.db().openReadOnlyConnection();
        try {
            Statement st = c.createStatement();
            ResultSet rs = st.executeQuery("SELECT password FROM staff ORDER BY code");
            while (rs.next()) {
                passwords.add(rs.getString(1));
            }
            st.close();
        } finally {
            c.close();
        }
        assertEquals(7, passwords.size());
        DataModule data = new DataModule();
        data.install(lending.ctx);
        try {
            Map<String, Object> all = map(lending.ctx.call("data.query", args("source", "lending", "sql",
                    "SELECT code, password, name FROM staff ORDER BY code")));
            List<Object> rows = list(all.get("rows"));
            assertEquals(7, rows.size());
            for (Object row : rows) {
                assertEquals("<redacted>", list(row).get(1), String.valueOf(list(row).get(0)));
            }
            assertEquals(Arrays.asList("100", "<redacted>", "Alice Archer"), rows.get(0), "other columns as read");
            Map<String, Object> one = map(lending.ctx.call("data.query", args("source", "lending", "sql",
                    "SELECT s.password FROM staff s WHERE s.code = ?", "params", Collections.singletonList("100"))));
            assertEquals(Collections.singletonList(Collections.singletonList("<redacted>")), one.get("rows"));
            for (Map<String, Object> result : Arrays.asList(all, one)) {
                assertNoValueIn(passwords, result);
            }
        } finally {
            data.uninstall();
        }
    }

    private static void assertNoValueIn(List<String> secrets, Object json) {
        if (json instanceof Map) {
            for (Object value : ((Map<?, ?>) json).values()) {
                assertNoValueIn(secrets, value);
            }
        } else if (json instanceof List) {
            for (Object value : (List<?>) json) {
                assertNoValueIn(secrets, value);
            }
        } else if (json != null) {
            assertFalse(secrets.contains(String.valueOf(json)), "a password left the JVM: " + json);
        }
    }

    @Test
    void slipsAreNewestFirstAndBounded() throws Exception {
        lending.signOn();
        for (String amount : Arrays.asList("3.50", "5.00")) {
            lending.send("Barcode", "5000000000017");
            lending.send("Key.DUE", null);
            lending.send("Key.DEPOSIT", amount);
        }
        List<Object> two = list(map(lending.ctx.call("lending.slip", args("last", 2L))).get("slips"));
        assertEquals(2, two.size());
        assertEquals(Long.valueOf(1002), map(two.get(0)).get("loanId"));
        assertEquals(Long.valueOf(1001), map(two.get(1)).get("loanId"));
        assertEquals(ErrorCodes.INVALID_PARAMS, lending.ctx.fails("lending.slip", args("last", 0L)).error().code());
        assertEquals(ErrorCodes.INVALID_PARAMS, lending.ctx.fails("lending.slip", args("last", 101L)).error().code());

        DeviceAdapter printer = lending.ctx.spi.first(DeviceAdapter.class);
        assertEquals("printer", printer.id());
        assertEquals("printer", printer.kind());
        assertEquals(Collections.singleton("lastSlip"), printer.actions());
        Map<String, Object> state = printer.state();
        assertEquals(Long.valueOf(2), state.get("slips"));
        assertEquals("TOOL LIBRARY - MILL LANE", state.get("header"));
        assertEquals(Long.valueOf(1002), map(state.get("last")).get("loanId"));
        assertEquals(Long.valueOf(1002), map(printer.act("lastSlip", JsonView.of(args()))).get("loanId"));
        RpcException unknown = assertThrows(RpcException.class, () -> printer.act("print", JsonView.of(args())));
        assertEquals(ErrorCodes.INVALID_PARAMS, unknown.error().code());
    }

    @Test
    void theDataSourceOpensTheApplicationsReadOnlyConnection() throws Exception {
        DataSourceProvider source = lending.ctx.spi.first(DataSourceProvider.class);
        assertEquals("lending", source.id());
        assertNull(source.query("SELECT 1", Collections.emptyList(), 1, 1000), "JDBC form only");
        Connection c = source.open();
        try {
            assertTrue(c.isReadOnly());
            Statement st = c.createStatement();
            ResultSet rs = st.executeQuery("SELECT name FROM staff WHERE code = '100'");
            assertTrue(rs.next());
            assertEquals("Alice Archer", rs.getString(1));
            assertThrows(SQLException.class, () -> st.executeUpdate("DELETE FROM staff"));
            st.close();
        } finally {
            c.close();
        }
    }

    @Test
    void appStateReportsTheEngineAndItsListenerCount() throws Exception {
        AppStateProvider provider = lending.ctx.spi.first(AppStateProvider.class);
        assertEquals("lending", provider.id());
        Map<String, Object> state = provider.appState();
        assertEquals(Boolean.TRUE, state.get("running"));
        assertEquals("SIGNED_OFF", state.get("name"));
        assertEquals(Long.valueOf(1), state.get("engineListeners"), "headless: only the module's listener");
        assertEquals(Boolean.FALSE, state.get("loanOpen"));
        assertNull(state.get("modalPrompt"));
        assertEquals("A", state.get("seed"));
        assertTrue(state.containsKey("dbPort"));
        assertNull(state.get("dbPort"), "this desk serves no database port");
    }

    @Test
    void anInputDroppedByTheEngineIsReportedAsNotAcknowledged() throws Exception {
        // Pre-checked against the current state but taken by the engine after another input changed the state:
        // the engine drops it, so the acknowledgement never comes and the call says so instead of claiming success.
        lending.signOn();
        long before = lending.ctx.events.lastSeq();
        assertTrue(lending.app.inbox().offer(ai.skillroy.testjava.samples.lending.LendingInput.key(
                ai.skillroy.testjava.samples.lending.KeyAction.SIGN_OFF)));
        Map<String, Object> result;
        try {
            result = map(lending.ctx.call("lending.input",
                    args("source", "Key.QTY", "value", "2", "ackTimeoutMs", 300L)));
        } catch (RpcException rejected) {
            // The engine had already signed off when the pre-check ran: also a correct answer.
            assertEquals(ErrorCodes.INPUT_REJECTED, rejected.error().code());
            return;
        }
        assertEquals(Boolean.TRUE, result.get("accepted"));
        assertEquals(Boolean.FALSE, result.get("acked"), "QTY is not valid in SIGNED_OFF and is dropped");
        assertNull(lending.ctx.events.await(before, TopicFilter.parse("input.acked"), args("source", "Key.QTY"), 0));
        assertFalse(lending.ctx.events.topics().isEmpty());
    }
}
