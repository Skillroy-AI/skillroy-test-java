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

package ai.skillroy.testjava.agent.core;

import ai.skillroy.testjava.agent.api.AgentLog;
import ai.skillroy.testjava.protocol.Volumes;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the token and discovery files owner-only and atomically (a temporary file in the same directory moved over
 * the target), so that a reader never sees a partial file, and guards the directory those files go into (contract
 * amendment A3, findings S7, S8, S15, S19). Whoever controls the directory can swap or plant discovery files and so
 * lure a controller to a different agent, so the agent writes only into a directory it can trust.
 *
 * <p>Three kinds of file system, the same rules as the controller's {@code SecureFiles}:
 * <ul>
 *   <li>POSIX attributes (Linux, macOS): directories are created {@code rwx------} and files {@code rw-------}; a
 *       directory is trusted when the current user owns it (and a symbolic link naming it) and group and others
 *       cannot write to it.</li>
 *   <li>ACLs without POSIX attributes (Windows; <em>not verified on Windows</em>: the judgement is unit-tested on
 *       built ACL values only): "me" is the owner of a file this process has just created (which covers elevated
 *       administrators and service accounts without matching names); directories and files are created with one
 *       ALLOW entry for "me" (inherited by a directory's children) and a file's ACL is read back after creation; a
 *       directory is trusted when "me" owns it and no ALLOW entry gives anyone but "me", the system account or the
 *       administrators group the right to add, delete or rename entries or to change its ACL, owner or
 *       attributes. Anything that cannot be read or resolved is refused, never repaired.</li>
 *   <li>Neither (for example FAT): nothing can be restricted or checked, so nothing is written; a generated token
 *       then stops the start, as for an untrusted directory.</li>
 * </ul>
 *
 * <p>Phase 6 security fix, pass 2, P2-L7-F1: the kind of what is written is the <em>volume's</em>
 * ({@link Volumes#protection}), never the file system provider's, so a FAT or exFAT volume is "neither" on every
 * platform; and what the POSIX branch creates is read back and must be exactly {@code rwx------} (a directory) or
 * {@code rw-------} (a file), else it is deleted and refused. Owners are still compared by the provider's view
 * ({@link #ownerKind}), so that the default-directory rule stays the controller's.
 *
 * <p>The default discovery directory ({@link #defaultDiscoveryDir()}, findings S8/S19) follows one deterministic rule
 * that the controller's {@code Discovery.defaultDir()} applies identically, so that both name the same directory
 * without being told.
 *
 * <p>Stateless apart from the shared {@link SecureRandom} and the lazily resolved current user; safe from any
 * thread.
 */
final class SecureFiles {

    /** ACL rights that let a principal plant, replace or remove entries of a directory, or take it over. */
    static final Set<AclEntryPermission> DIRECTORY_WRITE_RIGHTS = Collections.unmodifiableSet(EnumSet.of(
            AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA, AclEntryPermission.DELETE_CHILD,
            AclEntryPermission.DELETE, AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER,
            AclEntryPermission.WRITE_ATTRIBUTES, AclEntryPermission.WRITE_NAMED_ATTRS));

    /** Any right at all: a file holding a token may be accessible to "me" (and the built-in principals) only. */
    static final Set<AclEntryPermission> ANY_RIGHT =
            Collections.unmodifiableSet(EnumSet.allOf(AclEntryPermission.class));

    /** How a file system lets this class restrict access. */
    enum Protection { POSIX, ACL, NONE }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<PosixFilePermission> OWNER_RW =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> OWNER_RWX = EnumSet.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    /** The agent's own discovery and token files: {@code skillroy-<pid>-<startMs>.json|.token}. */
    private static final Pattern AGENT_FILE = Pattern.compile("skillroy-(\\d{1,19})-(\\d{1,19})\\.(json|token)");
    /** Sentinel for "the process uid cannot be determined". */
    private static final long UNKNOWN_UID = Long.MIN_VALUE;
    private static volatile Long processUid;

    private SecureFiles() {
    }

    // ---- the default discovery directory (findings S8, S19) ----

    /**
     * The default discovery directory, by the rule both sides apply (contract §2.5, findings S8/S19):
     * <ol>
     *   <li>candidate (a) is {@code ${java.io.tmpdir}/skillroy-<user>}, {@code <user>} being {@code user.name}
     *       reduced by {@link #safeUserName};</li>
     *   <li>candidate (b) is {@code ${user.home}/.skillroy/run}, a candidate only when {@code user.home} is set, not
     *       empty, not {@code "?"}, absolute and an existing directory;</li>
     *   <li>(b) is chosen exactly when it is a candidate and (a) exists, looked at without following links, with an
     *       owner other than the current user (a foreign directory, file or symbolic link); otherwise (a).</li>
     * </ol>
     * Only system properties and file-system facts both processes see alike are used: no environment variable and
     * no uid lookup. A directory the user owns but that others may write to is not "occupied": it is refused later,
     * never replaced by (b). The directory returned may not exist yet.
     */
    static File defaultDiscoveryDir() {
        return defaultDiscoveryDir(System.getProperty("java.io.tmpdir"), System.getProperty("user.name"),
                System.getProperty("user.home")).toFile();
    }

    /** The rule of {@link #defaultDiscoveryDir()} over explicit inputs. */
    static Path defaultDiscoveryDir(String tmpDir, String userName, String userHome) {
        return choose(tmpCandidate(tmpDir, userName), homeCandidate(userHome));
    }

    /** Step 3 of the rule: {@code b} exactly when it is a candidate (not null) and {@code a} is another user's. */
    static Path choose(Path a, Path b) {
        return b != null && occupiedByAnotherUser(a) ? b : a;
    }

    /** Candidate (a), {@code <tmpDir>/skillroy-<sanitised user>}. */
    static Path tmpCandidate(String tmpDir, String userName) {
        return Paths.get(tmpDir == null ? "" : tmpDir, "skillroy-" + safeUserName(userName));
    }

    /**
     * Candidate (b), {@code <userHome>/.skillroy/run}, or null when {@code userHome} is null, empty, {@code "?"}, not
     * absolute or not an existing directory.
     */
    static Path homeCandidate(String userHome) {
        if (userHome == null || userHome.isEmpty() || "?".equals(userHome)) {
            return null;
        }
        try {
            Path home = Paths.get(userHome);
            if (!home.isAbsolute() || !Files.isDirectory(home)) {
                return null;
            }
            return home.resolve(".skillroy").resolve("run");
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * True when {@code dir} exists (not following links) and its owner is not the current user: the predicate of the
     * default-directory rule, decided exactly as the controller's {@code Discovery.occupiedByAnotherUser} decides it.
     * On POSIX file systems by uid where it is known, else by name (the controller compares the principal of
     * {@code user.name}, which is the same user); elsewhere the owner must equal, or have the name of, the principal
     * looked up from {@code user.name} (the owner of a new file when that lookup fails). An owner that cannot be read
     * or a current user that cannot be determined is no reason to fall back.
     */
    static boolean occupiedByAnotherUser(Path dir) {
        try {
            UserPrincipal owner = Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS);
            if (ownerKind(dir) == Protection.POSIX) {
                return !isMine(dir, owner, LinkOption.NOFOLLOW_LINKS);
            }
            UserPrincipal me = NamedUser.PRINCIPAL;
            return me != null && !(owner.equals(me) || owner.getName().equals(me.getName()));
        } catch (IOException | RuntimeException e) {
            // Missing (the common case), or an owner that cannot be read: no reason to fall back.
            return false;
        }
    }

    /**
     * The user name with every character outside {@code [A-Za-z0-9._-]} dropped (null gives ""). Must stay identical
     * to the controller's rule, or a controller would look for the agent's files in another directory.
     */
    static String safeUserName(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_'
                    || c == '-') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---- writing ----

    /**
     * Writes the file owner-only (see the class comment), atomically, after {@link #prepareDirectory} accepted its
     * directory.
     *
     * @throws IOException if the directory is unsafe, the file system can restrict nothing, or the file cannot be
     *     written
     */
    static void writeOwnerOnly(Path target, byte[] content) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        prepareDirectory(dir);
        Path tmp = dir.resolve(target.getFileName().toString() + ".tmp-" + hex(6));
        try {
            createOwnerOnlyFile(tmp);
            OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                out.write(content);
            } finally {
                out.close();
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Creates a new, empty file that only the current user may access; with ACLs the ACL is read back. */
    private static void createOwnerOnlyFile(Path file) throws IOException {
        switch (protection(file)) {
            case POSIX:
                Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_RW));
                Files.setPosixFilePermissions(file, OWNER_RW);
                readBack(file, OWNER_RW, "write");
                return;
            case ACL:
                UserPrincipal me = fileOwner();
                if (me == null) {
                    throw new IOException("cannot write " + file + " owner-only: the current user cannot be "
                            + "determined");
                }
                Files.createFile(file, aclAttribute(ownerOnlyAcl(me, false)));
                AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS);
                String problem = view == null ? "its ACL cannot be read"
                        : aclProblem(view.getOwner(), view.getAcl(), me, BuiltIns.SYSTEM, BuiltIns.ADMINISTRATORS,
                        ANY_RIGHT);
                if (problem != null) {
                    Files.deleteIfExists(file);
                    throw new IOException("cannot write " + file + " owner-only: " + problem);
                }
                return;
            default:
                throw new IOException("cannot write " + file + " owner-only: its file system has neither POSIX "
                        + "permissions nor ACLs" + Volumes.note(file));
        }
    }

    /**
     * Creates the directory if it is missing (owner-only, parents included), then checks that it is safe to write
     * into. An existing directory is never changed.
     *
     * @throws IOException if it cannot be created, or it or the symbolic link naming it is owned by another user, or
     *     others may write to it, or the file system can restrict nothing
     */
    static void prepareDirectory(Path dir) throws IOException {
        Path abs = dir.toAbsolutePath();
        Protection protection = protection(abs);
        if (protection == Protection.NONE) {
            throw new IOException("directory " + abs + " cannot be used: its file system has neither POSIX "
                    + "permissions nor ACLs" + Volumes.note(abs) + ", so nothing written there can be restricted to the"
                    + " current user");
        }
        if (!Files.isDirectory(abs)) {
            if (protection == Protection.POSIX) {
                Path parent = abs.getParent();
                if (parent != null) {
                    Files.createDirectories(parent, PosixFilePermissions.asFileAttribute(OWNER_RWX));
                }
                boolean created = false;
                try {
                    Files.createDirectory(abs, PosixFilePermissions.asFileAttribute(OWNER_RWX));
                    created = true;
                } catch (java.nio.file.FileAlreadyExistsException raced) {
                    // Created concurrently (by the controller or another agent); checked below.
                }
                if (created) {
                    readBack(abs, OWNER_RWX, "create");
                }
            } else {
                createAclDirectories(abs);
            }
        }
        if (protection == Protection.POSIX) {
            checkSafe(abs);
        } else {
            checkAcl(abs);
        }
    }

    /** Creates every missing directory of {@code dir} with an owner-only ACL. */
    private static void createAclDirectories(Path dir) throws IOException {
        UserPrincipal me = fileOwner();
        if (me == null) {
            throw new IOException("cannot create " + dir + " owner-only: the current user cannot be determined");
        }
        List<Path> missing = new ArrayList<Path>();
        for (Path p = dir; p != null && !Files.exists(p, LinkOption.NOFOLLOW_LINKS); p = p.getParent()) {
            missing.add(0, p);
        }
        for (Path p : missing) {
            try {
                Files.createDirectory(p, aclAttribute(ownerOnlyAcl(me, true)));
            } catch (java.nio.file.FileAlreadyExistsException raced) {
                // Created concurrently; checked below.
            }
        }
    }

    /** The POSIX check of {@link #prepareDirectory}; package-private for tests. */
    static void checkSafe(Path dir) throws IOException {
        if (Files.isSymbolicLink(dir)) {
            PosixFileAttributes link = Files.readAttributes(dir, PosixFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!isMine(dir, link.owner(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("directory " + dir + " is a symbolic link owned by " + link.owner().getName()
                        + ", not by the current user " + System.getProperty("user.name"));
            }
        }
        PosixFileAttributes attrs = Files.readAttributes(dir, PosixFileAttributes.class);
        if (!attrs.isDirectory()) {
            throw new IOException(dir + " is not a directory");
        }
        if (!isMine(dir, attrs.owner())) {
            throw new IOException("directory " + dir + " is owned by " + attrs.owner().getName()
                    + ", not by the current user " + System.getProperty("user.name"));
        }
        Set<PosixFilePermission> permissions = attrs.permissions();
        if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
            throw new IOException("directory " + dir + " is writable by group or others ("
                    + PosixFilePermissions.toString(permissions) + ")");
        }
    }

    /** The ACL check of {@link #prepareDirectory} (findings S7/S15). */
    private static void checkAcl(Path dir) throws IOException {
        UserPrincipal me = fileOwner();
        if (me == null) {
            throw new IOException("directory " + dir + " cannot be checked: the current user cannot be determined");
        }
        if (Files.isSymbolicLink(dir)) {
            UserPrincipal linkOwner = Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS);
            if (!me.equals(linkOwner)) {
                throw new IOException("directory " + dir + " is a link owned by " + linkOwner.getName()
                        + ", not by the current user");
            }
        }
        BasicFileAttributes basic = Files.readAttributes(dir, BasicFileAttributes.class);
        if (!basic.isDirectory()) {
            throw new IOException(dir + " is not a directory");
        }
        AclFileAttributeView view = Files.getFileAttributeView(dir, AclFileAttributeView.class);
        String problem = view == null ? "its ACL cannot be read"
                : aclProblem(view.getOwner(), view.getAcl(), me, BuiltIns.SYSTEM, BuiltIns.ADMINISTRATORS,
                DIRECTORY_WRITE_RIGHTS);
        if (problem != null) {
            throw new IOException("directory " + dir + " cannot be used: " + problem + " (remedy, for example: "
                    + "icacls <dir> /inheritance:r /grant:r <user>:(OI)(CI)F)");
        }
    }

    /**
     * The pure ACL judgement of findings S7/S15, identical to the controller's: why an object with this owner and
     * ACL must not be trusted, or null. The owner must be {@code me}; no ALLOW entry may give a principal other than
     * {@code me}, {@code system} or {@code administrators} any of {@code rights}. A built-in principal that could not
     * be resolved (null) is not exempt, so an entry naming it is refused as well.
     *
     * @param rights {@link #DIRECTORY_WRITE_RIGHTS} for a directory, {@link #ANY_RIGHT} for a file holding a secret
     */
    static String aclProblem(UserPrincipal owner, List<AclEntry> acl, UserPrincipal me, UserPrincipal system,
                             UserPrincipal administrators, Set<AclEntryPermission> rights) {
        if (me == null) {
            return "the current user cannot be determined";
        }
        if (owner == null || !owner.equals(me)) {
            return "it is owned by " + (owner == null ? "nobody known" : owner.getName())
                    + ", not by the current user";
        }
        if (acl == null) {
            return "its ACL cannot be read";
        }
        for (AclEntry entry : acl) {
            if (entry.type() != AclEntryType.ALLOW) {
                continue;
            }
            UserPrincipal who = entry.principal();
            if (me.equals(who) || who.equals(system) || who.equals(administrators)) {
                continue;
            }
            Set<AclEntryPermission> granted = EnumSet.noneOf(AclEntryPermission.class);
            granted.addAll(entry.permissions());
            granted.retainAll(rights);
            if (!granted.isEmpty()) {
                Set<String> names = new TreeSet<String>();
                for (AclEntryPermission p : granted) {
                    names.add(p.name());
                }
                StringBuilder joined = new StringBuilder();
                for (String name : names) {
                    joined.append(joined.length() == 0 ? "" : ", ").append(name);
                }
                return "its ACL allows " + who.getName() + " " + joined;
            }
        }
        return null;
    }

    /** An ACL with one ALLOW entry giving {@code me} every right (inherited by children for a directory). */
    static List<AclEntry> ownerOnlyAcl(UserPrincipal me, boolean directory) {
        AclEntry.Builder entry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(me)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) {
            entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        }
        return Collections.singletonList(entry.build());
    }

    private static FileAttribute<List<AclEntry>> aclAttribute(final List<AclEntry> acl) {
        return new FileAttribute<List<AclEntry>>() {
            @Override
            public String name() {
                return "acl:acl";
            }

            @Override
            public List<AclEntry> value() {
                return acl;
            }
        };
    }

    /**
     * The protection available for files in {@code path}'s volume: {@link Volumes#protection}, decided by the volume
     * (its {@code FileStore}) and never by the file system provider, whose views are the same for every path (security
     * fix P2-L7-F1).
     */
    static Protection protection(Path path) {
        switch (Volumes.protection(path)) {
            case POSIX:
                return Protection.POSIX;
            case ACL:
                return Protection.ACL;
            default:
                return Protection.NONE;
        }
    }

    /**
     * The kind of owner view {@code path}'s file system provider offers (POSIX, else ACL, else neither): how owners are
     * compared ({@link #isMine}, {@link #occupiedByAnotherUser}), as the controller compares them; not what may be
     * written there ({@link #protection}).
     */
    static Protection ownerKind(Path path) {
        Set<String> views = path.getFileSystem().supportedFileAttributeViews();
        return views.contains("posix") ? Protection.POSIX : views.contains("acl") ? Protection.ACL : Protection.NONE;
    }

    /**
     * The POSIX read-back of security fix P2-L7-F1: {@code created} (just created here) must read back exactly
     * {@code expected}, not following links; otherwise it is deleted and refused, since its file system does not keep
     * the permissions it was given (a FAT or exFAT volume reads back {@code rwx------} on macOS, {@code rwxr-xr-x} on
     * Linux, where {@code chmod} succeeds and changes nothing).
     */
    private static void readBack(Path created, Set<PosixFilePermission> expected, String what) throws IOException {
        Set<PosixFilePermission> actual;
        try {
            actual = Files.readAttributes(created, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .permissions();
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(created);
            throw new IOException("cannot " + what + " " + created + " owner-only: its permissions cannot be read back ("
                    + e.getClass().getName() + "); it was deleted");
        }
        String problem = readBackProblem(expected, actual);
        if (problem != null) {
            Files.deleteIfExists(created);
            throw new IOException("cannot " + what + " " + created + " owner-only: " + problem + Volumes.note(created)
                    + "; it was deleted (use a directory on a file system that keeps permissions)");
        }
    }

    /** Why permissions read back as {@code actual} are not the {@code expected} ones just set, or null. */
    static String readBackProblem(Set<PosixFilePermission> expected, Set<PosixFilePermission> actual) {
        if (actual != null && actual.equals(expected)) {
            return null;
        }
        return "it reads back " + (actual == null ? "nothing" : PosixFilePermissions.toString(actual)) + " where "
                + PosixFilePermissions.toString(expected) + " was asked, so its file system does not keep permissions";
    }

    /**
     * True if the file belongs to the user this JVM runs as. POSIX: by uid where both are known (a user without a
     * password-database entry has no usable name), else by name. ACL file systems: the owner must be the owner of a
     * file this process has just created.
     */
    private static boolean isMine(Path path, UserPrincipal owner, LinkOption... options) {
        if (ownerKind(path) == Protection.ACL) {
            UserPrincipal me = fileOwner();
            return me != null && me.equals(owner);
        }
        long uid = processUid();
        if (uid != UNKNOWN_UID) {
            try {
                Object fileUid = Files.getAttribute(path, "unix:uid", options);
                if (fileUid instanceof Number) {
                    return ((Number) fileUid).longValue() == uid;
                }
            } catch (IOException | RuntimeException ignored) {
                // No "unix" attribute view here: compare names.
            }
        }
        String user = System.getProperty("user.name");
        return user != null && owner != null && user.equals(owner.getName());
    }

    /** The uid of this process, or {@link #UNKNOWN_UID}. Computed once. */
    private static long processUid() {
        Long cached = processUid;
        if (cached == null) {
            cached = Long.valueOf(lookUpProcessUid());
            processUid = cached;
        }
        return cached.longValue();
    }

    private static long lookUpProcessUid() {
        try {
            // JDK API on every Unix JDK (jdk.security.auth since Java 9); used reflectively so that a runtime
            // without it falls back instead of failing to link.
            Class<?> type = Class.forName("com.sun.security.auth.module.UnixSystem");
            Object system = type.getConstructor().newInstance();
            return ((Number) type.getMethod("getUid").invoke(system)).longValue();
        } catch (Throwable ignored) {
            // Not available: try /proc.
        }
        try {
            Path self = Paths.get("/proc/self");
            if (Files.exists(self)) {
                Object uid = Files.getAttribute(self, "unix:uid");
                if (uid instanceof Number) {
                    return ((Number) uid).longValue();
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Unknown: owner names are compared instead.
        }
        return UNKNOWN_UID;
    }

    /** "Me" for the ACL rule: the owner of a file this process has just created; null if unknown. */
    static UserPrincipal fileOwner() {
        return FileOwner.OWNER;
    }

    // ---- stale files (contract amendment A23) ----

    /**
     * Deletes the discovery and token files in {@code dir} that carry this JVM's {@code pid} and a start time other
     * than {@code startTimeMs}: they belong to an earlier JVM that had the same pid. Another pid's files are never
     * touched (whether that process is alive cannot be told portably on Java 8), nor anything that is not a regular
     * file. Call only after {@link #prepareDirectory} accepted {@code dir}. Never throws: failures are logged.
     *
     * @return the number of files deleted
     */
    static int deleteStaleOwnFiles(Path dir, long pid, long startTimeMs, AgentLog log) {
        int deleted = 0;
        DirectoryStream<Path> entries = null;
        try {
            entries = Files.newDirectoryStream(dir);
            for (Path entry : entries) {
                Path name = entry.getFileName();
                Matcher m = name == null ? null : AGENT_FILE.matcher(name.toString());
                if (m == null || !m.matches() || !String.valueOf(pid).equals(m.group(1))
                        || String.valueOf(startTimeMs).equals(m.group(2))) {
                    continue;
                }
                try {
                    if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) && Files.deleteIfExists(entry)) {
                        deleted++;
                        log.info("deleted the stale file " + entry + " of an earlier JVM with this pid");
                    }
                } catch (IOException | RuntimeException e) {
                    log.warn("cannot delete the stale file " + entry + ": " + e);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("cannot look for stale files in " + dir + ": " + e);
        } finally {
            if (entries != null) {
                try {
                    entries.close();
                } catch (IOException ignored) {
                    // Nothing to do.
                }
            }
        }
        return deleted;
    }

    static void deleteQuietly(String path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(new File(path).toPath());
        } catch (IOException | RuntimeException ignored) {
            // A stale file is detected by pid and start time.
        }
    }

    /** {@code bytes} random bytes as lower-case hex. */
    static String hex(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** The principal of {@code user.name}, as the controller resolves it; resolved on first use only. */
    private static final class NamedUser {
        static final UserPrincipal PRINCIPAL = resolve();

        private NamedUser() {
        }

        private static UserPrincipal resolve() {
            try {
                return FileSystems.getDefault().getUserPrincipalLookupService()
                        .lookupPrincipalByName(System.getProperty("user.name"));
            } catch (IOException | RuntimeException e) {
                // A user without a database entry: ask the file system who we are.
                return fileOwner();
            }
        }
    }

    /** Resolved on first use only: probing creates a temporary file. */
    private static final class FileOwner {
        static final UserPrincipal OWNER = ownerOfNewFile();

        private FileOwner() {
        }

        private static UserPrincipal ownerOfNewFile() {
            Path probe = null;
            try {
                probe = Files.createTempFile("skillroy-owner-", ".tmp");
                return Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException | RuntimeException e) {
                return null;
            } finally {
                if (probe != null) {
                    try {
                        Files.deleteIfExists(probe);
                    } catch (IOException | RuntimeException ignored) {
                        // An empty probe file in the temp directory is harmless.
                    }
                }
            }
        }
    }

    /** The system account and the administrators group, resolved once; null where they cannot be resolved. */
    private static final class BuiltIns {
        static final UserPrincipal SYSTEM = lookup("SYSTEM", false);
        static final UserPrincipal ADMINISTRATORS = lookup("Administrators", true);

        private BuiltIns() {
        }

        private static UserPrincipal lookup(String name, boolean group) {
            try {
                UserPrincipalLookupService lookup = FileSystems.getDefault().getUserPrincipalLookupService();
                return group ? lookup.lookupPrincipalByGroupName(name) : lookup.lookupPrincipalByName(name);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }
}
