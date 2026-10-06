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

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The scenario key {@code ui: {clickRung, strict}} (contract amendment A29): how faithfully the base {@code click}
 * verb drives the UI.
 *
 * <p>Why a scenario setting: the primitive's own default rung stays {@code ACTION}, which is focus-independent and
 * safe for interactive use, where bringing a window to the front would disturb the person at the screen. A suite
 * whose purpose is UI verification opts in per scenario with {@code clickRung: COMPONENT}: a {@code click} step that
 * names no {@code rung} then compiles to {@code swing.click} with {@code rung: COMPONENT}, {@code activateWindow: true}
 * and, unless {@code strict} is true, {@code fallback: ACTION}. A click that had to fall back is recorded as degraded
 * fidelity and makes the best outcome of the run {@code DEGRADED}; with {@code strict} the click that cannot focus
 * does not fire and the step fails.
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param clickRung {@link #ACTION} or {@link #COMPONENT}
 * @param strict true: no fall-back is added to the clicks the setting compiles
 */
public record UiSettings(String clickRung, boolean strict) {

    /** The focus-independent rung, the primitive's default. */
    public static final String ACTION = "ACTION";

    /** The rung that focuses the component first (with window activation when the setting applies it). */
    public static final String COMPONENT = "COMPONENT";

    /** The behaviour of a scenario without the {@code ui} key: {@code clickRung: ACTION}, {@code strict: false}. */
    public static final UiSettings DEFAULT = new UiSettings(ACTION, false);

    /**
     * Normalises the rung to upper case.
     *
     * @throws IllegalArgumentException if {@code clickRung} is neither ACTION nor COMPONENT (in any case)
     */
    public UiSettings {
        Objects.requireNonNull(clickRung, "clickRung");
        String rung = clickRung.strip().toUpperCase(Locale.ROOT);
        if (!rung.equals(ACTION) && !rung.equals(COMPONENT)) {
            throw new IllegalArgumentException("'clickRung' must be ACTION or COMPONENT, found \"" + clickRung + "\"");
        }
        clickRung = rung;
    }

    /** True when clicks that name no rung use the COMPONENT rung. */
    public boolean componentClicks() {
        return COMPONENT.equals(clickRung);
    }

    /** {@code {"clickRung": …, "strict": …}}, the shape written to {@code plan.json}; a new map on each call. */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("clickRung", clickRung);
        json.put("strict", strict);
        return json;
    }
}
