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

package ai.skillroy.testjava.spikes.bs14.probe;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The process's listening TCP sockets: the entries of /proc/self/net/tcp and tcp6 in state 0A (LISTEN) whose inode is
 * the target of one of /proc/self/fd/* (the container's namespace lists every process's sockets, so the fd mapping is
 * what makes the set this process's own). Each as "port/inode".
 */
final class ProcNet {

    private ProcNet() {
    }

    static boolean available() {
        return new File("/proc/self/net/tcp").canRead() && new File("/proc/self/fd").isDirectory();
    }

    static Set<String> listening() throws IOException {
        Set<String> own = new HashSet<String>();
        File[] fds = new File("/proc/self/fd").listFiles();
        if (fds != null) {
            for (File fd : fds) {
                try {
                    String target = Files.readSymbolicLink(fd.toPath()).toString();
                    if (target.startsWith("socket:[")) {
                        own.add(target.substring(8, target.length() - 1));
                    }
                } catch (IOException | UnsupportedOperationException gone) {
                    // the fd closed while listing
                }
            }
        }
        Set<String> result = new TreeSet<String>();
        for (String table : new String[] {"/proc/self/net/tcp", "/proc/self/net/tcp6"}) {
            Path p = Paths.get(table);
            if (!Files.isReadable(p)) {
                continue;
            }
            List<String> lines = Files.readAllLines(p, StandardCharsets.US_ASCII);
            for (int i = 1; i < lines.size(); i++) {
                String[] f = lines.get(i).trim().split("\\s+");
                if (f.length > 9 && "0A".equals(f[3]) && own.contains(f[9])) {
                    String local = f[1];
                    int port = Integer.parseInt(local.substring(local.indexOf(':') + 1), 16);
                    result.add(port + "/" + f[9]);
                }
            }
        }
        return result;
    }
}
