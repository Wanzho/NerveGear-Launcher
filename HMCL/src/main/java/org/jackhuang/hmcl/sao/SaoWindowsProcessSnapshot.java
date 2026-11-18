/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/// Reads only executable names and PIDs from a Windows Tool Help process snapshot.
@NotNullByDefault
final class SaoWindowsProcessSnapshot {
    /// Requests a system-wide list of processes without opening individual processes.
    private static final int SNAP_PROCESS = 0x00000002;
    /// The documented end of a process snapshot.
    private static final int NO_MORE_FILES = 18;

    /// Utility class.
    private SaoWindowsProcessSnapshot() { }

    /// Tests whether a complete snapshot contains another potential Java game process.
    static boolean hasOtherJava() throws IOException {
        Kernel kernel = Native.load("kernel32", Kernel.class);
        @Nullable Pointer snapshot = kernel.CreateToolhelp32Snapshot(SNAP_PROCESS, 0);
        if (snapshot == null || Pointer.nativeValue(snapshot) == -1L
                || (Native.POINTER_SIZE == 4 && Pointer.nativeValue(snapshot) == 0xffffffffL))
            throw new IOException("Windows 进程快照错误 " + Native.getLastError());
        try {
            long currentPid = ProcessHandle.current().pid();
            boolean foundCurrent = false;
            Entry entry = new Entry();
            entry.size = entry.size();
            boolean available = kernel.Process32FirstW(snapshot, entry);
            while (available) {
                long pid = Integer.toUnsignedLong(entry.pid);
                if (pid == currentPid) foundCurrent = true;
                else if (isJavaImage(Native.toString(entry.image))) return true;
                available = kernel.Process32NextW(snapshot, entry);
            }
            int error = Native.getLastError();
            if (error != NO_MORE_FILES || !foundCurrent)
                throw new IOException("Windows 进程快照不完整 " + error);
            return false;
        } finally {
            kernel.CloseHandle(snapshot);
        }
    }

    /// Only these executable names are considered by the Windows instance ownership check.
    static boolean isJavaImage(String image) {
        return image.equalsIgnoreCase("java.exe") || image.equalsIgnoreCase("javaw.exe");
    }

    /// Minimum Kernel32 bindings needed for a read-only snapshot.
    @NotNullByDefault
    public interface Kernel extends StdCallLibrary {
        /// Creates a non-inheritable snapshot handle.
        @Nullable Pointer CreateToolhelp32Snapshot(int flags, int pid);

        /// Reads the first process using the Unicode structure.
        boolean Process32FirstW(Pointer snapshot, Entry entry);

        /// Reads the next process from the same snapshot.
        boolean Process32NextW(Pointer snapshot, Entry entry);

        /// Releases the snapshot after enumeration.
        boolean CloseHandle(Pointer snapshot);
    }

    /// PROCESSENTRY32W layout. Only the PID and executable basename are examined.
    @NotNullByDefault
    @Structure.FieldOrder({"size", "usage", "pid", "defaultHeap", "module", "threads", "parent", "priority", "flags", "image"})
    public static class Entry extends Structure {
        /// Structure size required on entry to Process32FirstW.
        public int size;
        /// Legacy usage count.
        public int usage;
        /// Unsigned process ID.
        public int pid;
        /// Legacy pointer-sized heap identifier.
        public @Nullable Pointer defaultHeap;
        /// Legacy module identifier.
        public int module;
        /// Number of threads in the process.
        public int threads;
        /// Parent process ID.
        public int parent;
        /// Base thread priority.
        public int priority;
        /// Reserved flags.
        public int flags;
        /// Null-terminated UTF-16 executable basename populated by Windows.
        public char[] image = new char[260];
    }
}
