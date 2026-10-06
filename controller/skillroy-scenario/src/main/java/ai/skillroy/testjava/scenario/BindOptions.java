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

import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.data.CredentialsException;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * How a run binds its slots (phase2-contracts.md 6.6, 8.1): the mode, the environment id, the bindings root, the
 * credentials, the approver, whether {@code live} writes a bindings file, the approval label, the cost ledger and the
 * clock the bind budget is measured on.
 *
 * <p>Immutable; safe to share between threads if the approver and the clock are. The ledger is shared on purpose: a
 * suite passes one ledger to all its runs and asserts it is empty at the end.
 *
 * @param mode replay (the default), approve or live
 * @param envId the environment id, {@code [a-z0-9-]+}; default {@code default}
 * @param bindingsRoot the bindings root (one per invocation, decision D31), or null when none is known
 * @param credentials the invocation's credentials, loaded once before the agent is contacted; never null
 * @param approver asked in {@code approve} mode; default {@link Approver#REJECT_ALL}
 * @param writeBindings {@code live} only: write the bindings file with {@code approval.state = NOT_REQUIRED}
 * @param approvedBy the label written as {@code approval.by}; null = the approver's, else the mode name
 * @param ledger every model call of the runs that share these options; never null
 * @param clock what the bind budget is measured on; default the system UTC clock
 */
public record BindOptions(BindMode mode, String envId, Path bindingsRoot, Credentials credentials,
                          Approver approver, boolean writeBindings, String approvedBy, CostLedger ledger,
                          Clock clock) {

    /** The environment id when none is given. */
    public static final String DEFAULT_ENV = "default";

    /** System property: the mode. */
    public static final String PROP_MODE = "skillroy.mode";
    /** System property: the environment id. */
    public static final String PROP_ENV = "skillroy.env";
    /** System property: the bindings root. */
    public static final String PROP_BINDINGS = "skillroy.bindings";
    /** System property: {@code all} approves everything in {@code approve} mode. */
    public static final String PROP_APPROVE = "skillroy.approve";

    private static final Pattern ENV_ID = Pattern.compile("[a-z0-9-]+");

    /**
     * Fills defaults for null components.
     *
     * @throws IllegalArgumentException if the environment id is not {@code [a-z0-9-]+}
     */
    public BindOptions {
        mode = mode == null ? BindMode.REPLAY : mode;
        envId = envId == null ? DEFAULT_ENV : envId;
        if (!ENV_ID.matcher(envId).matches()) {
            throw new IllegalArgumentException("an environment id must match [a-z0-9-]+, found \"" + envId + "\"");
        }
        credentials = credentials == null ? Credentials.empty() : credentials;
        approver = approver == null ? Approver.REJECT_ALL : approver;
        ledger = ledger == null ? new CostLedger() : ledger;
        clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * {@code replay}, environment {@code default}, no bindings root, no credentials, {@link Approver#REJECT_ALL}, no
     * writing, no label, a new ledger, the system clock.
     */
    public static BindOptions defaults() {
        return new BindOptions(null, null, null, null, null, false, null, null, null);
    }

    /**
     * The options the JUnit glue uses when it is given no {@code RunOptions} (phase2-contracts.md 12.2):
     * {@code skillroy.mode}, {@code skillroy.env}, {@code skillroy.bindings}, {@code skillroy.credentials} (with the
     * location rule of {@link Credentials#locate}) and {@code skillroy.approve}. Loads the credentials file once.
     *
     * @throws CredentialsException if a credentials file was named and is missing, or is unreadable, malformed or
     *     too open
     * @throws IllegalArgumentException if the mode or the environment id is invalid
     */
    public static BindOptions fromSystemProperties() throws CredentialsException {
        return fromSystemProperties(null);
    }

    /**
     * {@link #fromSystemProperties()} with a default bindings root for when {@code skillroy.bindings} is not set (the
     * glue passes {@code bindings} in the parent of a scenario directory, decision D31).
     *
     * @param defaultBindingsRoot used when the property is absent; may be null
     */
    public static BindOptions fromSystemProperties(Path defaultBindingsRoot) throws CredentialsException {
        return fromProperties(System::getProperty, defaultBindingsRoot);
    }

    static BindOptions fromProperties(UnaryOperator<String> properties, Path defaultBindingsRoot)
            throws CredentialsException {
        String modeText = properties.apply(PROP_MODE);
        BindMode mode = modeText == null || modeText.isBlank() ? BindMode.REPLAY : BindMode.parse(modeText);
        String envText = properties.apply(PROP_ENV);
        String envId = envText == null || envText.isBlank() ? DEFAULT_ENV : envText.strip();
        String rootText = properties.apply(PROP_BINDINGS);
        Path root = rootText == null || rootText.isBlank() ? defaultBindingsRoot : Path.of(rootText.strip());
        String approve = properties.apply(PROP_APPROVE);
        Approver approver = approve != null && "all".equals(approve.strip().toLowerCase(Locale.ROOT))
                ? Approver.APPROVE_ALL : Approver.REJECT_ALL;
        Path file = Credentials.locate(null, root, envId);
        Credentials credentials = file == null ? Credentials.empty() : Credentials.load(file);
        return new BindOptions(mode, envId, root, credentials, approver, false, null, null, null);
    }

    /** A copy with another mode. */
    public BindOptions withMode(BindMode newMode) {
        return new BindOptions(newMode, envId, bindingsRoot, credentials, approver, writeBindings, approvedBy,
                ledger, clock);
    }

    /** A copy with another environment id. */
    public BindOptions withEnvId(String newEnvId) {
        return new BindOptions(mode, newEnvId, bindingsRoot, credentials, approver, writeBindings, approvedBy,
                ledger, clock);
    }

    /** A copy with another bindings root. */
    public BindOptions withBindingsRoot(Path root) {
        return new BindOptions(mode, envId, root, credentials, approver, writeBindings, approvedBy, ledger, clock);
    }

    /** A copy with other credentials. */
    public BindOptions withCredentials(Credentials newCredentials) {
        return new BindOptions(mode, envId, bindingsRoot, newCredentials, approver, writeBindings, approvedBy,
                ledger, clock);
    }

    /** A copy with another approver. */
    public BindOptions withApprover(Approver newApprover) {
        return new BindOptions(mode, envId, bindingsRoot, credentials, newApprover, writeBindings, approvedBy,
                ledger, clock);
    }

    /** A copy that does or does not write the bindings file in {@code live} mode. */
    public BindOptions withWriteBindings(boolean on) {
        return new BindOptions(mode, envId, bindingsRoot, credentials, approver, on, approvedBy, ledger, clock);
    }

    /** A copy with another approval label. */
    public BindOptions withApprovedBy(String label) {
        return new BindOptions(mode, envId, bindingsRoot, credentials, approver, writeBindings, label, ledger,
                clock);
    }

    /** A copy with another ledger. */
    public BindOptions withLedger(CostLedger newLedger) {
        return new BindOptions(mode, envId, bindingsRoot, credentials, approver, writeBindings, approvedBy,
                newLedger, clock);
    }

    /** A copy with another clock. */
    public BindOptions withClock(Clock newClock) {
        return new BindOptions(mode, envId, bindingsRoot, credentials, approver, writeBindings, approvedBy, ledger,
                newClock);
    }

    /** The mode, the environment and the root; never a credential. */
    @Override
    public String toString() {
        return "BindOptions[" + mode.label() + ", env=" + envId + ", bindings=" + bindingsRoot + ", " + credentials
                + ", writeBindings=" + writeBindings + "]";
    }
}
