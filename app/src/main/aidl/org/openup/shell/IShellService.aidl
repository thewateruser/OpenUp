package org.openup.shell;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/**
 * Runs inside a Shizuku user service process with shell (adb) or root privileges.
 * Every method reports failures through the returned Bundle (key "error") instead of throwing.
 */
interface IShellService {
    /** Shizuku requires this exact transaction code for destroying user services. */
    void destroy() = 16777114;

    int getUid() = 1;

    /**
     * Runs argv directly (no shell interpretation). stdin may be null.
     * Result keys: "exitCode" (int), "stdout", "stderr" (strings, truncated to 128 KiB), "error".
     */
    Bundle exec(in String[] argv, in ParcelFileDescriptor stdin) = 2;

    /**
     * Writes a tar archive of the contents of dirPath into output (then closes it). Top level names in
     * excludes are skipped. Result keys: "files", "dirs", "bytes" (long), "skipped", "shrunk" (int),
     * "skippedPaths" (String[]), "error".
     */
    Bundle tar(String dirPath, in ParcelFileDescriptor output, in String[] excludes) = 3;

    /**
     * Replaces the contents of dirPath with the tar archive read from input. The archive is staged
     * first, so existing data is only replaced if extraction fully succeeds.
     * Result keys: "files", "dirs", "bytes" (long), "skipped" (int), "error".
     */
    Bundle untar(String dirPath, in ParcelFileDescriptor input) = 4;

    /** Total size in bytes of all regular files below path, -1 if it does not exist, -2 on error. */
    long sizeOf(String path) = 5;

    /** Recursively deletes path. Returns true if it no longer exists. */
    boolean delete(String path) = 6;

    /** Result keys: "json" (saved networks), "error". */
    Bundle wifiBackup() = 7;

    /** Result keys: "restored", "skipped" (int), "messages" (String[]), "error". */
    Bundle wifiRestore(String json) = 8;

    /**
     * Like untar, but keeps files in dirPath that are not in the archive (files with the same
     * name are overwritten). Used for restoring folders on shared storage.
     */
    Bundle untarMerge(String dirPath, in ParcelFileDescriptor input) = 9;

    /** Names of the sub-directories of path, or null if it cannot be read. */
    String[] listDirectories(String path) = 10;
}
