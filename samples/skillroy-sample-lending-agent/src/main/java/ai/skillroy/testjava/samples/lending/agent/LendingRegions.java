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

import ai.skillroy.testjava.agent.api.spi.RegionResolver;
import ai.skillroy.testjava.samples.lending.NumberPad;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * The desk's number pad as twelve regions (phase7-contracts.md section 12.2): {@code supports} a {@link NumberPad},
 * {@code regionAt} the key under a point, {@code rectOf} a key's rectangle, {@code regions} all twelve (so a capture
 * with marks shows one mark per key). Region ids {@code key:0} to {@code key:9}, {@code key:.} and {@code key:clear}.
 *
 * <p>Called on the event dispatch thread; fast, stateless, changes nothing.
 */
final class LendingRegions implements RegionResolver {

    @Override
    public boolean supports(Component component) {
        return component instanceof NumberPad;
    }

    @Override
    public Region regionAt(Component component, Point point) {
        if (!(component instanceof NumberPad)) {
            return null;
        }
        String label = NumberPad.keyAt(point);
        return label == null ? null : new Region(NumberPad.regionId(label), NumberPad.keyRect(label));
    }

    @Override
    public Rectangle rectOf(Component component, String id) {
        if (!(component instanceof NumberPad) || id == null) {
            return null;
        }
        String label = NumberPad.labelOf(id);
        return label == null ? null : NumberPad.keyRect(label);
    }

    @Override
    public List<Region> regions(Component component) {
        List<Region> out = new ArrayList<Region>();
        if (component instanceof NumberPad) {
            for (String label : NumberPad.LABELS) {
                out.add(new Region(NumberPad.regionId(label), NumberPad.keyRect(label)));
            }
        }
        return out;
    }
}
