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

package ai.skillroy.testjava.scenario;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JunitXmlReporterTest {

    private static final StepResult S1 = new StepResult("s1", "signOn", 9, Status.PASS, null, List.of(),
            Duration.ofMillis(120));

    private static final RunResult PASS = new RunResult("basic-loan", Status.PASS, null, null, null, Map.of(),
            List.of(S1), List.of(), Duration.ofMillis(1500));
    private static final RunResult FAIL = new RunResult("wrong-total", Status.FAIL, FailureKind.ASSERTION, "s2",
            "expectation failed: loan.total {\"equals\":\"9.00\"}: expected \"9.00\" but was \"7.00\"",
            Map.of("path", "loan.total", "snapshot", "- button \"DUE\" [ref=h12] (disabled)\n"),
            List.of(S1, new StepResult("s2", "expect", 10, Status.FAIL, "expectation failed", List.of(),
                    Duration.ofMillis(10_000))), List.of(), Duration.ofMillis(11_000));
    private static final RunResult ERROR = new RunResult("needs-printer", Status.ERROR,
            FailureKind.PRECONDITION_FAILED, null, "unmet requirements: printer: missing", Map.of(), List.of(),
            List.of(), Duration.ofMillis(5));
    private static final RunResult DEGRADED = new RunResult("guarded", Status.DEGRADED, null, null, null, Map.of(),
            List.of(new StepResult("s1", "expect", 3, Status.DEGRADED, null, List.of("s1: loan.auth skipped"),
                    Duration.ZERO)), List.of("s1: loan.auth {\"size\":0} skipped: capability x is missing"),
            Duration.ofMillis(40));

    private static Document parse(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static Element testcase(Document doc, String name) {
        NodeList cases = doc.getElementsByTagName("testcase");
        for (int i = 0; i < cases.getLength(); i++) {
            Element e = (Element) cases.item(i);
            if (e.getAttribute("name").equals(name)) {
                return e;
            }
        }
        throw new AssertionError("no testcase " + name);
    }

    private static Element child(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }

    @Test
    void writesOneTestcasePerScenarioPlusEnvironment(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("reports/TEST-skillroy.xml");
        new JunitXmlReporter().write(file, "skillroy.scenarios", List.of(PASS, FAIL, ERROR, DEGRADED), null);
        Document doc = parse(Files.readString(file));
        Element suite = doc.getDocumentElement();
        assertEquals("testsuite", suite.getTagName());
        assertEquals("skillroy.scenarios", suite.getAttribute("name"));
        assertEquals("5", suite.getAttribute("tests"));
        assertEquals("1", suite.getAttribute("failures"));
        assertEquals("1", suite.getAttribute("errors"));
        assertEquals("1", suite.getAttribute("skipped"));
        assertEquals("12.545", suite.getAttribute("time"));
        assertEquals(5, doc.getElementsByTagName("testcase").getLength());

        Element env = testcase(doc, "environment");
        assertEquals(0, env.getChildNodes().getLength());

        Element pass = testcase(doc, "basic-loan");
        assertEquals("skillroy.scenarios", pass.getAttribute("classname"));
        assertEquals("1.500", pass.getAttribute("time"));
        assertEquals(null, child(pass, "failure"));
        assertTrue(child(pass, "system-out").getTextContent().contains("s1 signOn (line 9) PASS 0.120 s"));

        Element failure = child(testcase(doc, "wrong-total"), "failure");
        assertEquals("ASSERTION", failure.getAttribute("type"));
        assertEquals("ASSERTION at s2: expectation failed: loan.total {\"equals\":\"9.00\"}: expected \"9.00\" but"
                + " was \"7.00\"", failure.getAttribute("message"));
        assertTrue(failure.getTextContent().contains("\"path\": \"loan.total\""), failure.getTextContent());
        assertTrue(failure.getTextContent().contains("UI at failure:\n- button \"DUE\" [ref=h12] (disabled)"));

        Element error = child(testcase(doc, "needs-printer"), "error");
        assertEquals("PRECONDITION_FAILED", error.getAttribute("type"));
        assertEquals("PRECONDITION_FAILED: unmet requirements: printer: missing", error.getAttribute("message"));

        Element skipped = child(testcase(doc, "guarded"), "skipped");
        assertEquals("DEGRADED: skipped expectations: s1: loan.auth {\"size\":0} skipped: capability x is missing",
                skipped.getAttribute("message"));
    }

    @Test
    void degradedAsFailureOrPass() throws Exception {
        Document asFailure = parse(new JunitXmlReporter(RunOptions.DegradedAs.FAILURE)
                .render("s", List.of(DEGRADED), null));
        Element failure = child(testcase(asFailure, "guarded"), "failure");
        assertEquals("DEGRADED", failure.getAttribute("type"));
        assertTrue(failure.getAttribute("message").startsWith("DEGRADED: skipped expectations: "));
        assertEquals("1", asFailure.getDocumentElement().getAttribute("failures"));

        Document asPass = parse(new JunitXmlReporter(RunOptions.DegradedAs.PASS).render("s", List.of(DEGRADED),
                null));
        Element pass = testcase(asPass, "guarded");
        assertEquals(null, child(pass, "failure"));
        assertEquals(null, child(pass, "skipped"));
        assertEquals("0", asPass.getDocumentElement().getAttribute("skipped"));
        assertEquals("0", asPass.getDocumentElement().getAttribute("failures"));

        Document asSkipped = parse(new JunitXmlReporter(null).render("s", List.of(DEGRADED), null));
        assertEquals("1", asSkipped.getDocumentElement().getAttribute("skipped"));
    }

    @Test
    void environmentFailureIsAnError() throws Exception {
        Document doc = parse(new JunitXmlReporter().render("s", List.of(), "attach failed: no such pid\nmore"));
        Element error = child(testcase(doc, "environment"), "error");
        assertEquals("ENVIRONMENT", error.getAttribute("type"));
        assertEquals("attach failed: no such pid", error.getAttribute("message"));
        assertEquals("attach failed: no such pid\nmore", error.getTextContent());
        assertEquals("1", doc.getDocumentElement().getAttribute("tests"));
        assertEquals("1", doc.getDocumentElement().getAttribute("errors"));
    }

    @Test
    void escapesTextAndAttributes() throws Exception {
        String nasty = "a<b>&\"c\" 'd' \u0001\u0007 😀 \uD800 ]]> line\nnext";
        RunResult result = new RunResult("esc", Status.FAIL, FailureKind.ASSERTION, "s1", nasty,
                Map.of("snapshot", nasty), List.of(new StepResult("s1", "type", 1, Status.FAIL, nasty, List.of(),
                        Duration.ZERO)), List.of(), Duration.ZERO);
        String xml = new JunitXmlReporter().render("suite & <co>", List.of(result), null);
        assertFalse(xml.contains("\u0001") || xml.contains("\u0007") || xml.contains("\uD800 "), "invalid chars");
        Document doc = parse(xml);
        assertEquals("suite & <co>", doc.getDocumentElement().getAttribute("name"));
        Element failure = child(testcase(doc, "esc"), "failure");
        assertEquals("ASSERTION at s1: a<b>&\"c\" 'd' ?? 😀 ? ]]> line", failure.getAttribute("message"));
        assertTrue(failure.getTextContent().contains("a<b>&\"c\" 'd' ?? 😀 ? ]]> line\nnext"));
        assertTrue(child(testcase(doc, "esc"), "system-out").getTextContent().contains("s1 type (line 1) FAIL"));
        assertEquals("a&lt;b&gt;&amp;&quot;c&quot; &apos;d&apos; &#10;", JunitXmlReporter.attr("a<b>&\"c\" 'd' \n"));
    }

    @Test
    void scrubbingHappensBeforeEscapingSoTheReportStaysWellFormed() throws Exception {
        // Security finding S27: a secret in an attribute, a detail value, the UI snapshot and a step message.
        String secret = "0123456789abcdef0123456789abcdef";
        RunResult result = new RunResult("leak", Status.FAIL, FailureKind.ASSERTION, "s1", "rejected " + secret,
                Map.of("echo", "token=" + secret, "snapshot", "- textbox \"" + secret + "\" [ref=h1]\n"),
                List.of(new StepResult("s1", "type", 2, Status.FAIL, "typed " + secret, List.of(), Duration.ZERO)),
                List.of(), Duration.ZERO);
        String xml = new JunitXmlReporter().render("suite " + secret, List.of(result), "env " + secret,
                s -> s.replace(secret, "<redacted>"));
        assertFalse(xml.contains(secret), xml);
        Document doc = parse(xml);
        Element failure = child(testcase(doc, "leak"), "failure");
        assertEquals("ASSERTION at s1: rejected <redacted>", failure.getAttribute("message"));
        assertTrue(failure.getTextContent().contains("token=<redacted>"), failure.getTextContent());
        assertTrue(failure.getTextContent().contains("textbox \"<redacted>\""), failure.getTextContent());
        assertTrue(child(testcase(doc, "leak"), "system-out").getTextContent().contains("typed <redacted>"));
        assertEquals("suite <redacted>", doc.getDocumentElement().getAttribute("name"));
        assertEquals("env <redacted>", child(testcase(doc, "environment"), "error").getTextContent());
        assertAttributesAndTextFree(doc.getDocumentElement(), secret);
    }

    @Test
    void aSecretThatIsAMarkupWordLeavesTheMarkupAlone() throws Exception {
        String xml = new JunitXmlReporter().render("s", List.of(FAIL, PASS), null,
                s -> s.replace("testcase", "<redacted>"));
        Document doc = parse(xml);
        assertEquals(3, doc.getElementsByTagName("testcase").getLength());
        assertEquals(new JunitXmlReporter().render("s", List.of(FAIL, PASS), null),
                new JunitXmlReporter().render("s", List.of(FAIL, PASS), null, null), "null scrub is identity");
    }

    @Test
    void degradedFidelityIsListedWithTheSkippedExpectations() throws Exception {
        RunResult both = new RunResult("both", Status.DEGRADED, null, null, null, Map.of(),
                List.of(new StepResult("s1", "click", 3, Status.DEGRADED, null, List.of(), Duration.ZERO,
                        List.of("s1: click fell back from COMPONENT to ACTION (FOCUS_FAILED: x)"))),
                List.of("s2: loan.auth skipped"), Duration.ZERO,
                List.of("s1: click fell back from COMPONENT to ACTION (FOCUS_FAILED: x)"));
        Document doc = parse(new JunitXmlReporter().render("s", List.of(both), null));
        assertEquals("DEGRADED: skipped expectations: s2: loan.auth skipped; degraded fidelity: s1: click fell back"
                + " from COMPONENT to ACTION (FOCUS_FAILED: x)", child(testcase(doc, "both"), "skipped")
                .getAttribute("message"));
        assertTrue(child(testcase(doc, "both"), "system-out").getTextContent()
                .contains("  degraded s1: click fell back"));
        assertEquals(List.of(), DEGRADED.degradedFidelity(), "the shape before A29 has none");
    }

    private static void assertAttributesAndTextFree(Element element, String secret) {
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            assertFalse(element.getAttributes().item(i).getNodeValue().contains(secret));
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element e) {
                assertAttributesAndTextFree(e, secret);
            } else {
                assertFalse(children.item(i).getNodeValue() != null
                        && children.item(i).getNodeValue().contains(secret));
            }
        }
    }

    /**
     * phase7-contracts.md 8.6 (D328): a run that was not executed is {@code <skipped message="SKIPPED: ..."/>} whatever
     * the DEGRADED mapping, and counts as skipped.
     */
    @Test
    void aSkippedRunIsSkippedWhateverDegradedAsSays() throws Exception {
        RunResult skipped = new RunResult("vision-live-only", Status.SKIPPED, null, null,
                "not executed: the plan needs a model while steps run (inference: run)", Map.of(), List.of(),
                List.of(), Duration.ZERO);
        for (RunOptions.DegradedAs mapping : RunOptions.DegradedAs.values()) {
            String xml = new JunitXmlReporter(mapping).render("suite", List.of(skipped), null);
            assertTrue(xml.contains("<skipped message=\"SKIPPED: not executed: the plan needs a model while steps run"
                    + " (inference: run)\"/>"), mapping + ": " + xml);
            assertTrue(xml.contains("skipped=\"1\"") && xml.contains("failures=\"0\"") && xml.contains(
                    "errors=\"0\""), mapping + ": " + xml);
        }
    }
}
