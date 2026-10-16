package xyz.mdhv.asom.desktop.mac

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/**
 * One lending node per Mac (macos.md 3.2). Two logged-in users could each run an agent, so a node takes an exclusive lock on
 * `/var/tmp/xyz.mdhv.asom.node.lock` before entering SERVING; a second user's node stays ARMED with the local reason
 * `another-user-node`. Borrowing is unaffected.
 *
 * Choices the spec leaves open (mac ERRATA MAC-LOCK-1, MAC-LOCK-2):
 *  - the file is created with mode 0666, not the spec's 0644: with 0644 a second user cannot open it for writing, so could never
 *    take the exclusive lock even after the first user's node is gone;
 *  - it is opened with NOFOLLOW_LINKS and created with CREATE_NEW, so a symbolic link planted in the world-writable `/var/tmp`
 *    is never followed and never chmod-ed; a lock file that exists but cannot be opened for writing is reported as
 *    [Result.Unavailable], not silently ignored;
 *  - the file is never deleted (in a sticky directory only its owner could), and nothing is written to it.
 * A local user can always stop lending by holding the lock. So they can by other means; this is a correctness guard against two
 * of the owner's own agents, not a security boundary.
 */
class NodeLock(private val path: Path = Path.of(DEFAULT_PATH)) {
    sealed interface Result {
        class Acquired(val handle: AutoCloseable) : Result

        /** Another node (another user's, or another instance in this process) holds it: stay ARMED with `another-user-node`. */
        data object HeldByOther : Result

        /** The lock could not be tried (unsafe file, no permission): stay ARMED with `lock-unavailable` and say why. */
        class Unavailable(val reason: String) : Result
    }

    /**
     * POSIX record locks belong to the PROCESS, and closing ANY descriptor of the file drops all of the process's locks on it. So a
     * second attempt in this JVM (the doctor checking the lending slot while the node holds it, or a second [NodeLock] object) must
     * never open the file at all: it is answered from a per-process registry, before any descriptor exists. A test found this:
     * without the registry, a failed second attempt silently released the first holder's lock (mac ERRATA MAC-LOCK-3).
     */
    fun tryAcquire(): Result {
        val key = registryKey()
        synchronized(held) { if (!held.add(key)) return Result.HeldByOther }
        var acquired = false
        try {
            val r = tryAcquireFile(key)
            acquired = r is Result.Acquired
            return r
        } finally {
            if (!acquired) synchronized(held) { held.remove(key) }
        }
    }

    private fun registryKey(): String = try {
        path.toAbsolutePath().normalize().let { p -> p.parent?.toRealPath()?.resolve(p.fileName)?.toString() ?: p.toString() }
    } catch (_: IOException) {
        path.toAbsolutePath().normalize().toString()
    }

    private fun tryAcquireFile(key: String): Result {
        val channel = try {
            open()
        } catch (e: AccessDeniedException) {
            return probeReadOnly()
        } catch (e: IOException) {
            return Result.Unavailable("cannot open the lock file ${path}: ${e.message ?: e::class.simpleName}")
        }
        val lock: FileLock? = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            channel.close()
            return Result.HeldByOther
        } catch (e: IOException) {
            channel.close()
            return Result.Unavailable("cannot lock ${path}: ${e.message ?: e::class.simpleName}")
        }
        if (lock == null) {
            channel.close()
            return Result.HeldByOther
        }
        return Result.Acquired(
            AutoCloseable {
                try {
                    lock.release()
                } finally {
                    try {
                        channel.close()
                    } finally {
                        synchronized(held) { held.remove(key) }
                    }
                }
            },
        )
    }

    private fun open(): FileChannel {
        try {
            val created = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            try {
                makeOpenToAllUsers(path)
            } catch (e: Exception) {
                // A file we made but could not open up is still a usable lock for this user; another user's node will report Unavailable.
            }
            return created
        } catch (_: FileAlreadyExistsException) {
            return FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        }
    }

    private fun makeOpenToAllUsers(p: Path) {
        val view = java.nio.file.Files.getFileAttributeView(p, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        view.setPermissions(PosixFilePermissions.fromString("rw-rw-rw-"))
    }

    /** The file exists and is not ours to write. Read-only, we can still tell whether someone holds it, but we can never take it. */
    private fun probeReadOnly(): Result {
        return try {
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { ch ->
                val shared = ch.tryLock(0, 1, true)
                if (shared == null) {
                    Result.HeldByOther
                } else {
                    shared.release()
                    Result.Unavailable("the lock file ${path} is not writable by this user, so this node cannot take it (it was made by another user with a narrower mode)")
                }
            }
        } catch (e: Exception) {
            Result.Unavailable("the lock file ${path} cannot be used: ${e.message ?: e::class.simpleName}")
        }
    }

    companion object {
        const val DEFAULT_PATH = "/var/tmp/xyz.mdhv.asom.node.lock"
        private val held = HashSet<String>()
    }
}
