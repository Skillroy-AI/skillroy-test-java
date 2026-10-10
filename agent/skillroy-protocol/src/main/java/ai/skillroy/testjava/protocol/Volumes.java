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

package ai.skillroy.testjava.protocol;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * What a volume lets a process restrict, decided per volume (phase1-contracts.md 2.5; Phase 6 security fix, pass 2,
 * P2-L7-F1): the one decision both sides' {@code SecureFiles}, the controller's {@code CredentialFiles} and the agent
 * API's jar rule {@code JarTrust} (the {@code modules} paths, a named spy jar, and {@code byteman.jar}, whose
 * {@code JarCheck} delegates to it) share. A file system <em>provider</em> reports the same views for every path (every
 * local path is POSIX on Linux and macOS, ACL on Windows), so it cannot tell a FAT or exFAT volume from any other; the
 * {@link FileStore} of the path, or of its nearest existing ancestor, can: POSIX when it supports the POSIX view, else
 * ACL when it supports the ACL view (on Windows, a volume that keeps persistent ACLs), else neither, and a volume whose
 * type is one of {@link #WITHOUT_PERMISSIONS} is neither whatever its views say (macOS mounts FAT and exFAT
 * {@code noowners} and reports the POSIX view for exFAT; Linux's {@code vfat} reports it too). Where the store cannot
 * be read, the provider's views decide, as they did before.
 *
 * <p>Stateless; safe from any thread. Java 8, JDK only (it runs in the target JVM too).
 */
public final class Volumes {

    /** How a volume lets a process restrict access to what it writes there. */
    public enum Protection { POSIX, ACL, NONE }

    /**
     * The volume types whose permissions the mount synthesises rather than stores (FAT and exFAT, as Linux, macOS and
     * Windows name them), compared without regard to case: nothing written there can be restricted to one user.
     */
    public static final Set<String> WITHOUT_PERMISSIONS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "msdos", "vfat", "fat", "fat12", "fat16", "fat32", "exfat", "umsdos")));

    private Volumes() {
    }

    /** The protection of the volume that holds {@code path} (or would hold it: its nearest existing ancestor's). */
    public static Protection protection(Path path) {
        Path probe = (path == null ? Paths.get("") : path).toAbsolutePath();
        FileStore store = store(probe);
        if (store == null) {
            Set<String> views = probe.getFileSystem().supportedFileAttributeViews();
            return protection(null, views.contains("posix"), views.contains("acl"));
        }
        String type;
        try {
            type = store.type();
        } catch (RuntimeException e) {
            type = null;
        }
        return protection(type, store.supportsFileAttributeView(PosixFileAttributeView.class),
                store.supportsFileAttributeView(AclFileAttributeView.class));
    }

    /** The pure rule: a type of {@link #WITHOUT_PERMISSIONS} is neither; else POSIX, else ACL, else neither. */
    public static Protection protection(String type, boolean posix, boolean acl) {
        if (withoutPermissions(type)) {
            return Protection.NONE;
        }
        return posix ? Protection.POSIX : acl ? Protection.ACL : Protection.NONE;
    }

    /** True when {@code type} names a volume without permissions ({@link #WITHOUT_PERMISSIONS}). */
    public static boolean withoutPermissions(String type) {
        return type != null && WITHOUT_PERMISSIONS.contains(type.trim().toLowerCase(Locale.ROOT));
    }

    /** The type of the volume that holds {@code path} (its nearest existing ancestor's), or null when unknown. */
    public static String type(Path path) {
        FileStore store = store((path == null ? Paths.get("") : path).toAbsolutePath());
        try {
            return store == null ? null : store.type();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * {@code " (its volume is <type>, which keeps no permissions)"} when the volume of {@code path} is one of
     * {@link #WITHOUT_PERMISSIONS}, else empty: the note a refusal adds to its reason.
     */
    public static String note(Path path) {
        String type = type(path);
        return withoutPermissions(type) ? " (its volume is " + type + ", which keeps no permissions)" : "";
    }

    /** The store of {@code absolute} or of its nearest existing ancestor (links followed); null when none is read. */
    private static FileStore store(Path absolute) {
        for (Path p = absolute; p != null; p = p.getParent()) {
            try {
                if (Files.exists(p)) {
                    return Files.getFileStore(p);
                }
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }
}
