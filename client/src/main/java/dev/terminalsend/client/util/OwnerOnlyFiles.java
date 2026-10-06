package dev.terminalsend.client.util;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/** Account data (keys, history) is private: 0700 directories and 0600 files on POSIX systems. */
public final class OwnerOnlyFiles {

    private OwnerOnlyFiles() {
    }

    public static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    public static Path createDirectories(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            return dir;
        }
        if (posix()) {
            return Files.createDirectories(dir,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        return Files.createDirectories(dir);
    }

    /** Creates {@code file} empty with 0600 if missing, so tools like SQLite never create it world-readable. */
    public static void touch(Path file) throws IOException {
        createDirectories(file.toAbsolutePath().getParent());
        if (Files.exists(file)) {
            return;
        }
        if (posix()) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(file);
        }
    }

    /** Writes via a temp file in the same directory and an atomic move. */
    public static void write(Path file, byte[] content) throws IOException {
        Path dir = createDirectories(file.toAbsolutePath().getParent());
        Path tmp = posix()
                ? Files.createTempFile(dir, ".tmp", ".part",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(dir, ".tmp", ".part");
        try {
            Files.write(tmp, content);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
