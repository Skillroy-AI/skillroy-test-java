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
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.SettingsException;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * How a run binds its slots (phase2-contracts.md 6.6, 8.1; phase5-contracts.md 5.5, 7.1): the mode, the environment
 * id, the bindings root, the credentials, the approver, whether {@code live} writes a bindings file, the approval
 * label, the cost ledger, the clock the bind budget is measured on, the inference settings the contributors build their
 * gateways from, and whether {@code approve} reuses an unchanged, verified tier (b) value.
 *
 * <p>Immutable; safe to share between threads if the approver and the clock are. The ledger is shared on purpose: a
 * suite passes one ledger to all its runs and asserts it is empty at the end.
 *
 * @param mode replay (the default), approve, live or re-resolve
 * @param envId the environment id, {@code [a-z0-9-]+}; default {@code default}
 * @param bindingsRoot the bindings root (one per invocation, decision D31), or null when none is known
 * @param credentials the invocation's credentials, loaded once before the agent is contacted; never null
 * @param approver asked in {@code approve} mode; default {@link Approver#REJECT_ALL}
 * @param writeBindings {@code live}: write the bindings file ({@code NOT_REQUIRED}, or {@code REQUIRED} for a tier (b)
 *     value); {@code re-resolve}: write it ({@code APPROVED} by the job), false being the job's dry run
 * @param approvedBy the label written as {@code approval.by}; null = the approver's, else the mode name
 * @param ledger every model call of the runs that share these options; never null
 * @param clock what the bind budget is measured on; default the system UTC clock
 * @param inference the settings a {@code ResolverContributor} builds its gateway from; default
 *     {@link InferenceSettings#none()}; {@code replay} substitutes {@code none()} whatever is given
 * @param reuseBindings {@code approve} only (decision D82): a tier (b) slot whose recorded value is unchanged and passes
 *     every verifier now is offered again without a model call; default true
 */
public record BindOptions(BindMode mode, String envId, Path bindingsRoot, Credentials credentials,
                          Approver approver, boolean writeBindings, String approvedBy, CostLedger ledger,
                          Clock clock, InferenceSettings inference, boolean reuseBindings) {

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
        inference = inference == null ? InferenceSettings.none() : inference;
    }

    /**
     * The Phase 2 shape: inference settings {@link InferenceSettings#none()}, reuse on.
     *
     * @throws IllegalArgumentException if the environment id is not {@code [a-z0-9-]+}
     */
    public BindOptions(BindMode mode, String envId, Path bindingsRoot, Credentials credentials, Approver approver,
                       boolean writeBindings, String approvedBy, CostLedger ledger, Clock clock) {
        this(mode, envId, bindingsRoot, credentials, approver, writeBindings, approvedBy, ledger, clock, null, true);
    }

    /**
     * {@code replay}, environment {@code default}, no bindings root, no credentials, {@link Approver#REJECT_ALL}, no
     * writing, no label, a new ledger, the system clock, inference {@code none}, reuse on.
     */
    public static BindOptions defaults() {
        return new BindOptions(null, null, null, null, null, false, null, null, null, null, true);
    }

    /**
     * The options the JUnit glue uses when it is given no {@code RunOptions} (phase2-contracts.md 12.2):
     * {@code skillroy.mode}, {@code skillroy.env}, {@code skillroy.bindings}, {@code skillroy.credentials} (with the
     * location rule of {@link Credentials#locate}) and {@code skillroy.approve}, and the inference settings of the
     * system properties and the {@code inference.yaml} beside the credentials file (phase5-contracts.md 4.4). Loads the
     * credentials file once.
     *
     * @throws CredentialsException if a credentials file was named and is missing, or is unreadable, malformed or
     *     too open
     * @throws IllegalArgumentException if the mode or the environment id is invalid, if the mode is
     *     {@code re-resolve} ("re-resolve is a command: skillroy re-resolve", phase5-contracts.md 8.1), or if the
     *     inference settings are refused (the message of the {@link SettingsException}, which is the cause)
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
        return fromProperties(System::getProperty, System::getenv, defaultBindingsRoot);
    }

    static BindOptions fromProperties(UnaryOperator<String> properties, Path defaultBindingsRoot)
            throws CredentialsException {
        return fromProperties(properties, System::getenv, defaultBindingsRoot);
    }

    /**
     * The inference settings are read for the mode chosen, with {@code inference.yaml} looked for where the credentials
     * file was located, or in the bindings root's environment directory when there is none (phase5-contracts.md 4.4).
     */
    static BindOptions fromProperties(UnaryOperator<String> properties, UnaryOperator<String> environment,
                                      Path defaultBindingsRoot) throws CredentialsException {
        String modeText = properties.apply(PROP_MODE);
        BindMode mode = modeText == null || modeText.isBlank() ? BindMode.REPLAY : BindMode.parse(modeText);
        if (mode == BindMode.RE_RESOLVE) {
            throw new IllegalArgumentException("re-resolve is a command: skillroy re-resolve");
        }
        String envText = properties.apply(PROP_ENV);
        String envId = envText == null || envText.isBlank() ? DEFAULT_ENV : envText.strip();
        String rootText = properties.apply(PROP_BINDINGS);
        Path root = rootText == null || rootText.isBlank() ? defaultBindingsRoot : Path.of(rootText.strip());
        String approve = properties.apply(PROP_APPROVE);
        Approver approver = approve != null && "all".equals(approve.strip().toLowerCase(Locale.ROOT))
                ? Approver.APPROVE_ALL : Approver.REJECT_ALL;
        Path file = Credentials.locate(null, root, envId);
        Credentials credentials = file == null ? Credentials.empty() : Credentials.load(file);
        Path credentialsDir = file != null ? file.toAbsolutePath().getParent()
                : root != null && ENV_ID.matcher(envId).matches() ? root.resolve(envId) : null;
        InferenceSettings inference;
        try {
            inference = InferenceSettings.load(mode, credentialsDir, Map.of(), properties, environment);
        } catch (SettingsException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        return new BindOptions(mode, envId, root, credentials, approver, false, null, null, null, inference, true);
    }

    /** A copy with another mode. */
    public BindOptions withMode(BindMode newMode) {
        return new BindOptions(newMode, envId, bindingsRoot, credentials, approver, writeBindings, approvedBy,
                ledger, clock, inference, reuseBindings);
    }

    /** A copy with another environment id. */
    public BindOptions withEnvId(String newEnvId) {
        return new BindOptions(mode, newEnvId, bindingsRoot, credentials, approver, writeBindings, approvedBy,
                ledger, clock, inference, reuseBindings);
    }

    /** A copy with another bindings root. */
    public BindOptions withBindingsRoot(Path root) {
        return new BindOptions(mode, envId, root, credentials, approver, writeBindings, approvedBy, ledger, clock,
                inference, reuseBindings);
    }

    /** A copy with other credentials. */
    public BindOptions withCredentials(Credentials newCredentials) {
        return new BindOptions(mode, envId, bindingsRoot, newCredentials, approver, writeBindings, approvedBy,
                ledger, clock, inference, reuseBindings);
    }

    /** A copy with another approver. */
    public BindOptions withApprover(Approver newApprover) {
        return new BindOptions(mode, envId, bindingsRoot, credentials, newApprover, writeBindings, approvedBy,
                ledger, clock, inference, reuseBindings);
    }

    /** A copy that does or does not write the bindings file in {@code live} (and {@code re-resolve}) mode. */
    public BindOptions withWriteBindings(boolean on) {
        return new BindOptions(mode, envId, bindingsRoot, credentials, approver, on, approvedBy, ledger, clock,
                inference, reuseBindings);
    }

    /** A copy with another approval label. */
    public BindOptions withApprovedBy(String label) {
        return new BindOptions(mode, envId, bindingsRoot, credentials, approver, writeBindings, label, ledger,
                clock, inference, reuseBindings);
    }

    /** A copy with another ledger. */
    public BindOptions withLedger(CostLedger newLedger) {
        return new BindOptions(mode, envId, bindingsRoot, credentials, approver, writeBindings, approvedBy,
                newLedger, clock, inference, reuseBindings);
    }

    /** A copy with another clock. */
    public BindOptions withClock(Clock newClock) {
        return new BindOptions(mode, envId, bindingsRoot, credentials, approver, writeBindings, approvedBy, ledger,
                newClock, inference, reuseBindings);
    }

    /** A copy with other inference settings (null is {@link InferenceSettings#none()}). */
    public BindOptions withInference(InferenceSettings settings) {
        return new BindOptions(mode, envId, bindingsRoot, credentials, approver, writeBindings, approvedBy, ledger,
                clock, settings, reuseBindings);
    }

    /** A copy that does or does not reuse unchanged, verified tier (b) values in {@code approve} (D82). */
    public BindOptions withReuseBindings(boolean on) {
        return new BindOptions(mode, envId, bindingsRoot, credentials, approver, writeBindings, approvedBy, ledger,
                clock, inference, on);
    }

    /** The mode, the environment, the root and the gateway; never a credential. */
    @Override
    public String toString() {
        return "BindOptions[" + mode.label() + ", env=" + envId + ", bindings=" + bindingsRoot + ", " + credentials
                + ", writeBindings=" + writeBindings + ", inference=" + inference.gateway()
                + (reuseBindings ? "" : ", reuseBindings=false") + "]";
    }
}
