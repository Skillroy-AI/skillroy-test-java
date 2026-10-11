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

import ai.skillroy.testjava.controller.OutputFiles;
import ai.skillroy.testjava.scenario.image.ImageCompare;
import ai.skillroy.testjava.scenario.image.ImageSteps;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The baselines and anchors of one scenario's bindings file in one environment (phase7-contracts.md 8.7, decisions
 * D329, D355): the {@code images} section the binder read or carried forward, the PNGs beside the file under
 * {@code <bindingsRoot>/<envId>/<scenarioId>.images/}, and the baselines a person approved at their steps in this run,
 * which {@link #write} puts beside the old ones after the steps.
 *
 * <p>A PNG's path is derived from the label and the render key, never read from the entry's {@code file}; it must lie
 * inside the environment's directory, reached without following a link, and decode to the entry's raster hash
 * (threat model L7: a planted or hand-edited file never names a path to open).
 *
 * <p>Not thread-safe: one run's.
 */
final class ImageBaselines implements ImageSteps.Baselines {

    /** The largest PNG read from a bindings directory. */
    static final long MAX_PNG_BYTES = 64L * 1024 * 1024;

    private final String scenarioId;
    private final Path envDir;
    private final Map<String, Map<String, BindingsFile.ImageEntry>> entries = new TreeMap<>();
    private final Map<String, Map<String, BindingsFile.ImageEntry>> approved = new TreeMap<>();
    private final Map<String, byte[]> approvedPngs = new LinkedHashMap<>();

    /**
     * @param scenarioId the scenario
     * @param envDir {@code <bindingsRoot>/<envId>/}, or null when there is no bindings root
     * @param file the bindings file read or carried forward, or null
     */
    ImageBaselines(String scenarioId, Path envDir, BindingsFile file) {
        this.scenarioId = scenarioId;
        this.envDir = envDir;
        if (file != null) {
            file.images().forEach((label, byKey) -> entries.put(label, new TreeMap<>(byKey)));
        }
    }

    @Override
    public BindingsFile.ImageEntry entry(String label, String renderKey) {
        Map<String, BindingsFile.ImageEntry> fresh = approved.get(label);
        if (fresh != null && fresh.containsKey(renderKey)) {
            return fresh.get(renderKey);
        }
        Map<String, BindingsFile.ImageEntry> byKey = entries.get(label);
        return byKey == null ? null : byKey.get(renderKey);
    }

    @Override
    public Set<String> keys(String label) {
        Set<String> keys = new java.util.TreeSet<>();
        if (entries.containsKey(label)) {
            keys.addAll(entries.get(label).keySet());
        }
        if (approved.containsKey(label)) {
            keys.addAll(approved.get(label).keySet());
        }
        return keys;
    }

    @Override
    public BufferedImage image(String label, String renderKey) throws IOException {
        BindingsFile.ImageEntry entry = entry(label, renderKey);
        if (entry == null) {
            throw new IOException("no baseline " + label + " for rendering " + renderKey);
        }
        byte[] fresh = approvedPngs.get(label + "." + renderKey);
        byte[] bytes = fresh != null ? fresh : read(pngPath(label, renderKey));
        BufferedImage image = ImageCompare.decodePng(bytes);
        if (!ImageCompare.rasterHash(image).equals(entry.sha256())) {
            throw new IOException(BindingsFile.ImageEntry.fileName(scenarioId, label, renderKey)
                    + " is not the entry's raster (its hash differs from sha256)");
        }
        return image;
    }

    @Override
    public void approved(String label, String renderKey, BindingsFile.ImageEntry entry, byte[] png) {
        approved.computeIfAbsent(label, k -> new TreeMap<>()).put(renderKey, entry);
        approvedPngs.put(label + "." + renderKey, png.clone());
    }

    /** Whether a baseline was approved in this run. */
    boolean hasApprovals() {
        return !approved.isEmpty();
    }

    /** The {@code images} section with this run's approvals beside the old entries. */
    Map<String, Map<String, BindingsFile.ImageEntry>> merged() {
        Map<String, Map<String, BindingsFile.ImageEntry>> out = new TreeMap<>();
        entries.forEach((label, byKey) -> out.put(label, new TreeMap<>(byKey)));
        approved.forEach((label, byKey) -> out.computeIfAbsent(label, k -> new TreeMap<>()).putAll(byKey));
        return out;
    }

    /**
     * Writes this run's approved PNGs beside the bindings file ({@code <scenarioId>.images/<label>.<key>.png}, through
     * {@link OutputFiles}); the caller rewrites the file itself after.
     *
     * @throws IOException if a PNG cannot be written
     */
    void writeApprovedPngs() throws IOException {
        if (envDir == null) {
            throw new IOException("writing a baseline needs a bindings root");
        }
        for (Map.Entry<String, Map<String, BindingsFile.ImageEntry>> e : approved.entrySet()) {
            for (String key : e.getValue().keySet()) {
                // Phase 7 security fix, S140: written under the reader's rule (no link from the environment's
                // directory down), so that approve never writes where replay refuses to read.
                OutputFiles.write(BindingsFile.imageFile(envDir, scenarioId, e.getKey(), key),
                        approvedPngs.get(e.getKey() + "." + key));
            }
        }
    }

    /**
     * The PNG of a label and a key: derived, inside the environment's directory, no link on the way.
     *
     * @throws IOException if there is no bindings root or the path leaves the directory or passes a link
     */
    Path pngPath(String label, String renderKey) throws IOException {
        if (envDir == null) {
            throw new IOException("no bindings root: the baselines of " + scenarioId + " cannot be read");
        }
        // Phase 7 security fix, S140: the one walk the writers share (BindingsFile.imageFile), naming the link.
        Path file = BindingsFile.imageFile(envDir, scenarioId, label, renderKey);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("no file " + BindingsFile.ImageEntry.fileName(scenarioId, label, renderKey));
        }
        return file;
    }

    private static byte[] read(Path file) throws IOException {
        if (Files.size(file) > MAX_PNG_BYTES) {
            throw new IOException(file.getFileName() + " is larger than " + MAX_PNG_BYTES + " bytes");
        }
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return in.readNBytes((int) MAX_PNG_BYTES + 1);
        }
    }
}
