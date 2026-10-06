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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A printed slip: the posted loan's id and the printed lines. Immutable.
 */
public final class Slip {

    private final long loanId;
    private final List<String> lines;

    public Slip(long loanId, List<String> lines) {
        this.loanId = loanId;
        this.lines = Collections.unmodifiableList(new ArrayList<String>(lines));
    }

    public long loanId() {
        return loanId;
    }

    /** @return the printed lines, without line terminators */
    public List<String> lines() {
        return lines;
    }

    /** @return the lines joined with {@code "\n"} (no trailing newline) */
    public String text() {
        return String.join("\n", lines);
    }

    @Override
    public String toString() {
        return text();
    }
}
