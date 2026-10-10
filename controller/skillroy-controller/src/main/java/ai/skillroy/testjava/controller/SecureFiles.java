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

package ai.skillroy.testjava.controller;

import ai.skillroy.testjava.protocol.Volumes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Secrets and owner-only files: random tokens and nonces, the owner-only directory that holds options files, the
 * options file itself, and reading token files. Kept in one place because the token is a credential and every path
 * that writes or reads it must apply the same rules.
 *
 * <p>Three kinds of file system (contract §2.5):
 * <ul>
 *   <li>POSIX attributes: directories {@code rwx------} and files {@code rw-------}; a directory is trusted when the
 *       current user owns it and group and others cannot write to it (a symbolic link naming it must be the current
 *       user's too).</li>
 *   <li>ACLs without POSIX attributes (Windows; findings S7/S15, <em>not verified on Windows</em>: the judgement is
 *       unit-tested on built ACL values only): "me" is the owner of a file this process has just created; directories
 *       and files are created with one ALLOW entry for "me" (inherited by a directory's children) and read back; a
 *       directory is trusted when "me" owns it and no ALLOW entry gives anyone but "me", the system account or the
 *       administrators group the right to add, delete or rename entries or to change its ACL, owner or attributes.
 *       Anything that cannot be read or resolved is refused, never repaired.</li>
 *   <li>Neither: nothing can be restricted or checked, so no directory is trusted and nothing is written.</li>
 * </ul>
 *
 * <p>Phase 6 security fix, pass 2, P2-L7-F1: the kind is the <em>volume's</em> ({@link Volumes#protection}), never the
 * file system provider's, so a FAT or exFAT volume is "neither" on every platform; and what the POSIX branch creates is
 * read back and must be exactly {@code rwx------} (a directory) or {@code rw-------} (a file), else it is deleted and
 * refused.
 *
 * <p>Stateless apart from the shared {@link SecureRandom} and the lazily resolved current user; safe to call from any
 * thread.
 *
 * <p>Phase 6 (phase6-contracts.md 9.3, D138; built 2026-10-09, BW): public, so that every owner-only file or
 * directory the controller's other modules write goes through the same three branches (the planner's work directory
 * and its MCP configuration, the CDP trace directory, the MCP server's temporary output directory, the tests'
 * discovery directories). Exactly five members are public: {@link #ensureOwnerOnlyDirectory(Path)},
 * {@link #ensureOwnerOnlyDirectory(Path, String)}, {@link #createOwnerOnlyTempDirectory(Path, String)},
 * {@link #writeOwnerOnly(Path, byte[])} and {@link #untrustedDirectoryReason(Path)}; the token readers and
 * {@code writeOptionsFile} stay package-private (S6/S28). Each refuses on a file system with neither view, naming the
 * directory's role.
 */
public final class SecureFiles {

    /** Characters a token may contain (the agent's token file format). */
    private static final Pattern TOKEN_CHARS = Pattern.compile("[A-Za-z0-9._~+/=-]{1,1024}");

    /** Upper bound for a token file; a larger file is not a token file. */
    private static final int MAX_TOKEN_FILE_BYTES = 4096;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Set<PosixFilePermission> OWNER_DIR = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> OWNER_FILE = PosixFilePermissions.fromString("rw-------");

    /** ACL rights that let a principal plant, replace or remove entries of a directory, or take it over. */
    static final Set<AclEntryPermission> DIRECTORY_WRITE_RIGHTS = Collections.unmodifiableSet(EnumSet.of(
            AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA, AclEntryPermission.DELETE_CHILD,
            AclEntryPermission.DELETE, AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER,
            AclEntryPermission.WRITE_ATTRIBUTES, AclEntryPermission.WRITE_NAMED_ATTRS));

    /** Any right at all: a file holding a token may be accessible to "me" (and the built-in principals) only. */
    static final Set<AclEntryPermission> ANY_RIGHT =
            Collections.unmodifiableSet(EnumSet.allOf(AclEntryPermission.class));

    private SecureFiles() {
    }

    /** {@code bytes} random bytes from {@link SecureRandom}, as lower-case hex. */
    static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }

    /** A new token: 32 random bytes, hex (the agent generates its own tokens the same way). */
    static String newToken() {
        return randomHex(32);
    }

    /** A new launch nonce: 16 random bytes, hex. */
    static String newNonce() {
        return randomHex(16);
    }

    /** True when {@code token} has the token file format (it is a MAC key and never sent). */
    static boolean isValidToken(String token) {
        return token != null && TOKEN_CHARS.matcher(token).matches();
    }

    /**
     * The confined token reader of findings S6/S28: reads {@code tokenFile} only if it lies directly inside
     * {@code trustedDir} (compared as real paths, so a symbolically linked directory works), its name passes
     * {@code nameOk}, and, looked at without following links, it is a regular file (not a symbolic link) of at most
     * 4 KiB owned by the current user, on POSIX file systems with no group or other permission bit and a single
     * link. It is opened without following links and read up to 4 KiB, so a link swapped in after the checks is not
     * followed. Surrounding whitespace is removed and the token format checked.
     *
     * @param trustedDir a directory that has passed {@link #untrustedDirectoryReason(Path)}
     * @return the token, or null when any condition fails
     */
    static String readTokenIn(Path trustedDir, Path tokenFile, Predicate<String> nameOk) {
        if (trustedDir == null || tokenFile == null || nameOk == null) {
            return null;
        }
        try {
            Path file = tokenFile.toAbsolutePath().normalize();
            Path parent = file.getParent();
            Path name = file.getFileName();
            if (parent == null || name == null || !nameOk.test(name.toString())) {
                return null;
            }
            if (!parent.toRealPath().equals(trustedDir.toRealPath())) {
                return null;
            }
            BasicFileAttributes basic = Files.readAttributes(file, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!basic.isRegularFile() || basic.size() > MAX_TOKEN_FILE_BYTES || !ownedByCurrentUser(file)) {
                return null;
            }
            PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (posix != null) {
                for (PosixFilePermission p : posix.readAttributes().permissions()) {
                    if (p != PosixFilePermission.OWNER_READ && p != PosixFilePermission.OWNER_WRITE
                            && p != PosixFilePermission.OWNER_EXECUTE) {
                        return null;
                    }
                }
                if (linkCount(file) > 1) {
                    return null;
                }
            }
            byte[] bytes = readAtMost(file, MAX_TOKEN_FILE_BYTES);
            if (bytes == null) {
                return null;
            }
            String token = new String(bytes, StandardCharsets.UTF_8).strip();
            return isValidToken(token) ? token : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The {@code unix:nlink} attribute, or 1 when the file system does not report it. */
    private static int linkCount(Path file) {
        try {
            Object n = Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            return n instanceof Integer count ? count : 1;
        } catch (IOException | RuntimeException e) {
            return 1;
        }
    }

    /** At most {@code max} bytes of {@code file}, opened without following links; null when it holds more. */
    private static byte[] readAtMost(Path file, int max) throws IOException {
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = Files.newByteChannel(file, options)) {
            ByteBuffer buffer = ByteBuffer.allocate(max + 1);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Read until the buffer is full or the file ends.
            }
            if (buffer.position() > max) {
                return null;
            }
            byte[] bytes = new byte[buffer.position()];
            buffer.flip();
            buffer.get(bytes);
            return bytes;
        }
    }

    /**
     * Why the discovery directory {@code dir} must not be trusted, or null when it may be: the same check the agent
     * applies before writing token and discovery files there (contract §2.5, see the class comment). Anyone who can
     * write into the directory could plant discovery files or replace token and options files, so neither the agent
     * nor the controller uses such a directory.
     *
     * @return a short reason ("it does not exist", "it is writable by group or others (rwxrwxr-x)", ...) or null
     */
    public static String untrustedDirectoryReason(Path dir) {
        return untrustedDirectoryReason(dir, CurrentUser.PRINCIPAL);
    }

    /** {@link #untrustedDirectoryReason(Path)} with the current POSIX user given (tests pass another user). */
    static String untrustedDirectoryReason(Path dir, UserPrincipal me) {
        try {
            Protection protection = protection(dir);
            if (protection == Protection.NONE) {
                return Files.exists(dir) ? "its file system has neither POSIX permissions nor ACLs" + Volumes.note(dir)
                        + ", so it cannot be checked" : "it does not exist";
            }
            if (protection == Protection.ACL) {
                return nonPosixDirectoryReason(dir);
            }
            if (Files.isSymbolicLink(dir)) {
                UserPrincipal linkOwner = Files.readAttributes(dir, PosixFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS).owner();
                if (!isUser(linkOwner, me)) {
                    return "it is a symbolic link owned by " + linkOwner.getName() + ", not by the current user";
                }
            }
            BasicFileAttributes basic = Files.readAttributes(dir, BasicFileAttributes.class);
            if (!basic.isDirectory()) {
                return "it is not a directory";
            }
            PosixFileAttributes attributes = Files.readAttributes(dir, PosixFileAttributes.class);
            if (!isUser(attributes.owner(), me)) {
                return "it is owned by " + attributes.owner().getName() + ", not by the current user";
            }
            Set<PosixFilePermission> permissions = attributes.permissions();
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                return "it is writable by group or others (" + PosixFilePermissions.toString(permissions) + ")";
            }
            return null;
        } catch (NoSuchFileException e) {
            return "it does not exist";
        } catch (IOException | RuntimeException e) {
            return "its attributes cannot be read (" + e + ")";
        }
    }

    /** The ACL branch of the trust check, or the refusal where the file system has no ACLs either. */
    private static String nonPosixDirectoryReason(Path dir) throws IOException {
        AclFileAttributeView acl = Files.getFileAttributeView(dir, AclFileAttributeView.class);
        if (acl == null) {
            if (!Files.exists(dir)) {
                return "it does not exist";
            }
            return "its file system has neither POSIX permissions nor ACLs, so it cannot be checked";
        }
        UserPrincipal me = CurrentUser.fileOwner();
        if (me == null) {
            return "the current user cannot be determined (no file owner of a new file)";
        }
        if (Files.isSymbolicLink(dir)) {
            UserPrincipal linkOwner = Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS);
            if (!me.equals(linkOwner)) {
                return "it is a link owned by " + linkOwner.getName() + ", not by the current user";
            }
        }
        BasicFileAttributes basic = Files.readAttributes(dir, BasicFileAttributes.class);
        if (!basic.isDirectory()) {
            return "it is not a directory";
        }
        return aclProblem(acl.getOwner(), acl.getAcl(), me, BuiltIns.SYSTEM, BuiltIns.ADMINISTRATORS,
                DIRECTORY_WRITE_RIGHTS);
    }

    /**
     * The pure ACL judgement of findings S7/S15: why an object with this owner and ACL must not be trusted, or null.
     * The owner must be {@code me}; no ALLOW entry may give a principal other than {@code me}, {@code system} or
     * {@code administrators} any of {@code rights}. A built-in principal that could not be resolved (null) is not
     * exempt, so an entry naming it is refused as well.
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
                Set<String> names = new TreeSet<>();
                granted.forEach(p -> names.add(p.name()));
                return "its ACL allows " + who.getName() + " " + String.join(", ", names);
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
        return List.of(entry.build());
    }

    private static FileAttribute<List<AclEntry>> aclAttribute(List<AclEntry> acl) {
        return new FileAttribute<>() {
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
     * Creates {@code dir} if needed, together with any missing parent, owner-only (POSIX permissions or an owner-only
     * ACL, as the agent creates them), and checks that the directory can be trusted
     * ({@link #untrustedDirectoryReason(Path)}). An existing directory is never changed: one that fails the check is
     * refused, as the agent refuses it. The refusal names the discovery directory and the {@code discoveryDir}
     * option; {@link #ensureOwnerOnlyDirectory(Path, String)} names another role.
     *
     * @throws IOException if it cannot be created or cannot be trusted; the message says why and how to fix it
     */
    public static Path ensureOwnerOnlyDirectory(Path dir) throws IOException {
        return ensure(dir, "discovery directory", "choose another discoveryDir");
    }

    /**
     * {@link #ensureOwnerOnlyDirectory(Path)} for a directory of another role: the refusal names {@code what} (for
     * example "the test discovery directory") and the remedy without the discovery option (phase6-contracts.md 9.3).
     *
     * @param what the directory's role as the refusal names it, with or without a leading "the "
     */
    public static Path ensureOwnerOnlyDirectory(Path dir, String what) throws IOException {
        return ensure(dir, role(what), "choose another directory");
    }

    private static String role(String what) {
        String w = what == null || what.isBlank() ? "directory" : what.strip();
        return w.startsWith("the ") ? w.substring(4) : w;
    }

    private static Path ensure(Path dir, String role, String remedy) throws IOException {
        Path absolute = dir.toAbsolutePath().normalize();
        refuseWithoutProtection(absolute, role);
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            List<Path> missing = new ArrayList<>();
            for (Path p = absolute; p != null && !Files.exists(p, LinkOption.NOFOLLOW_LINKS); p = p.getParent()) {
                missing.add(0, p);
            }
            for (Path p : missing) {
                try {
                    createOwnerOnlyDirectory(p);
                } catch (FileAlreadyExistsException raced) {
                    // Created concurrently (by an agent or another controller); verified below.
                }
            }
        }
        String problem = untrustedDirectoryReason(absolute);
        if (problem != null) {
            throw new IOException("the " + role + " " + absolute + " cannot be used: " + problem
                    + " (it must be a directory of the current user that only its owner can write to, e.g. mode 700;"
                    + " fix it or " + remedy + ")");
        }
        return absolute;
    }

    /**
     * The refusal of a file system with neither POSIX permissions nor ACLs, in the agent's wording, naming the role
     * and the remedy; nothing is created.
     */
    private static void refuseWithoutProtection(Path path, String role) throws IOException {
        if (protection(path) == Protection.NONE) {
            throw new IOException("the " + role + " " + path + " cannot be used: its file system has neither POSIX "
                    + "permissions nor ACLs" + Volumes.note(path) + ", so nothing written there can be restricted to"
                    + " the current user (use a directory on a file system that has one of them)");
        }
    }

    /**
     * The POSIX read-back of security fix P2-L7-F1: {@code created} (just created by this class) must read back exactly
     * {@code expected}, not following links; otherwise it is deleted and refused, since its file system does not keep
     * the permissions it was given (a FAT or exFAT volume reads back {@code rwx------} on macOS, {@code rwxr-xr-x} on
     * Linux).
     */
    private static void readBack(Path created, Set<PosixFilePermission> expected, String what) throws IOException {
        Set<PosixFilePermission> actual;
        try {
            actual = Files.readAttributes(created, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .permissions();
        } catch (IOException | RuntimeException e) {
            deleteQuietly(created);
            throw new IOException("cannot " + what + " " + created + " owner-only: its permissions cannot be read back ("
                    + e.getClass().getName() + "); it was deleted");
        }
        String problem = readBackProblem(expected, actual);
        if (problem != null) {
            deleteQuietly(created);
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
     * A new owner-only directory with a random name ({@code prefix} and 16 hex digits) directly under {@code parent},
     * created through the same branches as {@link #ensureOwnerOnlyDirectory} and read back
     * ({@link #untrustedDirectoryReason(Path)}); the replacement for every {@code Files.createTempDirectory} of the
     * controller (phase6-contracts.md 9.3). {@code parent} must exist; it may be shared (the system's temporary
     * directory): the new directory is created exclusively, never reused.
     *
     * @throws IOException when the file system has neither view (nothing is created), when the directory cannot be
     *     created, or when the new directory fails the read-back (it is deleted)
     */
    public static Path createOwnerOnlyTempDirectory(Path parent, String prefix) throws IOException {
        Path base = parent.toAbsolutePath().normalize();
        String name = prefix == null ? "" : prefix;
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException("a prefix is a file name, not a path: " + prefix);
        }
        refuseWithoutProtection(base, "temporary directory " + name + "*" + " under");
        if (!Files.isDirectory(base)) {
            throw new IOException("cannot create a temporary directory under " + base + ": it is not a directory");
        }
        for (int attempt = 0; ; attempt++) {
            Path dir = base.resolve(name + randomHex(8));
            try {
                createOwnerOnlyDirectory(dir);
            } catch (FileAlreadyExistsException taken) {
                if (attempt < 8) {
                    continue;
                }
                throw taken;
            }
            String problem = untrustedDirectoryReason(dir);
            if (problem != null) {
                deleteQuietly(dir);
                throw new IOException("the temporary directory " + dir + " cannot be used: " + problem);
            }
            return dir;
        }
    }

    private static void createOwnerOnlyDirectory(Path dir) throws IOException {
        Path probe = dir.getParent() == null ? dir : dir.getParent();
        switch (protection(probe)) {
            case POSIX -> {
                Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(OWNER_DIR));
                readBack(dir, OWNER_DIR, "create");
            }
            case ACL -> {
                UserPrincipal me = CurrentUser.fileOwner();
                if (me == null) {
                    throw new IOException("cannot create " + dir + " owner-only: the current user cannot be "
                            + "determined");
                }
                Files.createDirectory(dir, aclAttribute(ownerOnlyAcl(me, true)));
            }
            default -> throw new IOException("cannot create " + dir + " owner-only: its file system has neither "
                    + "POSIX permissions nor ACLs" + Volumes.note(probe));
        }
    }

    /**
     * Writes an agent options file (Java properties format, ISO 8859-1 with unicode escapes, so any
     * {@code Properties.load} variant reads it) into {@code dir} under a new random name, readable by the current
     * user only. The file holds the token: callers delete it when they no longer need it.
     *
     * @return the absolute path of the new file
     */
    static Path writeOptionsFile(Path dir, Map<String, String> options) throws IOException {
        Path directory = ensureOwnerOnlyDirectory(dir);
        Properties properties = new Properties();
        for (Map.Entry<String, String> e : options.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                properties.setProperty(e.getKey(), e.getValue());
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        properties.store(bytes, "skillroy agent options (contains the agent's token; owner-only)");
        Path file = directory.resolve("skillroy-options-" + randomHex(8) + ".properties");
        writeOwnerOnly(file, bytes.toByteArray());
        return file;
    }

    /**
     * Creates {@code file} (it must not exist) owner-only and writes {@code content}. With ACLs the ACL is read back
     * after creation and the file deleted and refused if anybody but "me" and the built-in principals has access.
     *
     * @throws IOException also when the file system can restrict nothing
     */
    public static void writeOwnerOnly(Path file, byte[] content) throws IOException {
        switch (protection(file.toAbsolutePath().getParent())) {
            case POSIX -> {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_FILE));
                readBack(file, OWNER_FILE, "write");
            }
            case ACL -> {
                UserPrincipal me = CurrentUser.fileOwner();
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
                    deleteQuietly(file);
                    throw new IOException("cannot write " + file + " owner-only: " + problem);
                }
            }
            default -> throw new IOException("cannot write " + file + " owner-only: its file system has neither "
                    + "POSIX permissions nor ACLs" + Volumes.note(file.toAbsolutePath().getParent()) + ", so nothing"
                    + " written there can be restricted to the current user (use a directory on a file system that has"
                    + " one of them)");
        }
        try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            out.write(content);
        }
    }

    /** Deletes {@code file} if it exists; never throws. */
    static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException ignored) {
            // Best effort: an options file left behind is owner-only and in the controller's own directory.
        }
    }

    /**
     * True when the file's owner (not following links) is the current user: on POSIX file systems by principal (true
     * as well when the current user cannot be determined); elsewhere the owner must be the owner of a file this
     * process has just created, and anything that cannot be read is false. The check guards against files planted by
     * other users in a shared directory.
     */
    static boolean ownedByCurrentUser(Path path) {
        try {
            PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (view != null) {
                return isUser(view.getOwner(), CurrentUser.PRINCIPAL);
            }
            UserPrincipal me = CurrentUser.fileOwner();
            return me != null && me.equals(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** The current OS user as a POSIX file owner, or null when it cannot be determined. */
    static UserPrincipal currentUser() {
        return CurrentUser.PRINCIPAL;
    }

    /** True when {@code owner} is {@code me}; true as well when {@code me} could not be determined (null). */
    static boolean isUser(UserPrincipal owner, UserPrincipal me) {
        if (me == null) {
            return true;
        }
        return owner.equals(me) || owner.getName().equals(me.getName());
    }

    /** How a file system lets this class restrict access. */
    enum Protection { POSIX, ACL, NONE }

    /**
     * The protection available for files in {@code path}'s volume: {@link Volumes#protection}, decided by the volume
     * (its {@code FileStore}) and never by the file system provider, whose views are the same for every path (security
     * fix P2-L7-F1).
     */
    static Protection protection(Path path) {
        return switch (Volumes.protection(path)) {
            case POSIX -> Protection.POSIX;
            case ACL -> Protection.ACL;
            case NONE -> Protection.NONE;
        };
    }

    /** The current OS user, resolved once. */
    private static final class CurrentUser {

        /** The POSIX principal; null when it cannot be determined (the POSIX ownership check is then skipped). */
        static final UserPrincipal PRINCIPAL = resolve();

        private CurrentUser() {
        }

        private static UserPrincipal resolve() {
            try {
                return FileSystems.getDefault().getUserPrincipalLookupService()
                        .lookupPrincipalByName(System.getProperty("user.name"));
            } catch (IOException | RuntimeException e) {
                // A uid without a user database entry (common in containers): ask the file system who we are.
                return FileOwner.OWNER;
            }
        }

        /** "Me" for the ACL rule: the owner of a file this process has just created; null if unknown. */
        static UserPrincipal fileOwner() {
            return FileOwner.OWNER;
        }
    }

    /** Created on first use only: probing creates a temporary file. */
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
                deleteQuietly(probe);
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
