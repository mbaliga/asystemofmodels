package xyz.mdhv.asom.desktop.win.acl

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.UserPrincipal
import xyz.mdhv.asom.desktop.win.api.WinApiException
import xyz.mdhv.asom.desktop.win.api.WinApiUnavailableException

interface WinAcl {
    fun snapshot(path: Path): AclSnapshot

    /** Replaces the DACL with exactly [aces] (nothing inherited is kept). */
    fun replace(path: Path, aces: List<Ace>)
}

/** SID string <-> account name. The JNA implementation is Windows-only; a fake serves the tests. */
interface SidResolver {
    fun sidOf(accountName: String): String?
    fun accountNameOf(sid: String): String?
}

/** NTFS permission sets to the three rights the node reasons about, and back. Pure, so it is tested on any OS. */
object AclRights {
    private val writeBits = setOf(
        AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA,
        AclEntryPermission.WRITE_ATTRIBUTES, AclEntryPermission.WRITE_NAMED_ATTRS,
    )

    /**
     * Rights that let a holder change who else has access, or remove what another principal created: a holder of any of
     * them is FULL in effect (it can grant itself anything, or delete the service's socket and plant its own), so they are
     * a separate right a plan must name, never part of plain WRITE (windows ERRATA ERR-FX-HWM-4).
     */
    private val controlBits = setOf(
        AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER, AclEntryPermission.DELETE, AclEntryPermission.DELETE_CHILD,
    )
    private val readBits = setOf(
        AclEntryPermission.READ_DATA, AclEntryPermission.READ_ATTRIBUTES, AclEntryPermission.READ_NAMED_ATTRS,
        AclEntryPermission.EXECUTE, AclEntryPermission.READ_ACL,
    )
    private val fullBits = setOf(
        AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA, AclEntryPermission.DELETE,
        AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER, AclEntryPermission.READ_DATA,
    )

    fun rightsOf(perms: Set<AclEntryPermission>): Set<AclRight> {
        val out = HashSet<AclRight>(3)
        if (perms.containsAll(fullBits)) out += AclRight.FULL
        if (perms.any { it in controlBits }) out += AclRight.CONTROL
        if (perms.any { it in writeBits }) out += AclRight.WRITE
        if (perms.any { it in readBits }) out += AclRight.READ
        return out
    }

    fun permissionsFor(rights: Set<AclRight>): Set<AclEntryPermission> {
        val out = HashSet<AclEntryPermission>()
        if (AclRight.FULL in rights) out += AclEntryPermission.entries
        if (AclRight.CONTROL in rights) out += setOf(AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER)
        if (AclRight.WRITE in rights) {
            out += setOf(
                AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA, AclEntryPermission.WRITE_ATTRIBUTES,
                AclEntryPermission.WRITE_NAMED_ATTRS, AclEntryPermission.READ_ATTRIBUTES, AclEntryPermission.SYNCHRONIZE,
            )
        }
        if (AclRight.READ in rights) {
            out += setOf(
                AclEntryPermission.READ_DATA, AclEntryPermission.READ_ATTRIBUTES, AclEntryPermission.READ_NAMED_ATTRS,
                AclEntryPermission.READ_ACL, AclEntryPermission.EXECUTE, AclEntryPermission.SYNCHRONIZE,
            )
        }
        return out
    }
}

/**
 * The JDK's `AclFileAttributeView` (windows.md 10.1: "DACL apply/verify"). It exists only on NTFS on Windows; anywhere
 * else [snapshot] and [replace] throw [WinApiUnavailableException]. The Windows-only tests (`AfUnixAclIT`) are what shows
 * that a read-back honours [AclPlan] on a real disk.
 */
class JdkWinAcl(private val sids: SidResolver) : WinAcl {
    private fun view(path: Path): AclFileAttributeView =
        Files.getFileAttributeView(path, AclFileAttributeView::class.java)
            ?: throw WinApiUnavailableException("no ACL view for $path (not NTFS on Windows)")

    private fun sidOf(p: UserPrincipal): String? = sids.sidOf(p.name) ?: p.name.takeIf { it.startsWith("S-1-") }

    override fun snapshot(path: Path): AclSnapshot {
        val v = view(path)
        val aces = v.acl.map { e ->
            Ace(
                sid = sidOf(e.principal()) ?: "unresolved:${e.principal().name}",
                allow = e.type() == AclEntryType.ALLOW,
                rights = AclRights.rightsOf(e.permissions()),
                inherit = AclEntryFlag.FILE_INHERIT in e.flags() || AclEntryFlag.DIRECTORY_INHERIT in e.flags(),
            )
        }
        return AclSnapshot(sidOf(v.owner), aces)
    }

    override fun replace(path: Path, aces: List<Ace>) {
        val v = view(path)
        val lookup = path.fileSystem.userPrincipalLookupService
        val entries = aces.map { a ->
            val name = sids.accountNameOf(a.sid) ?: a.sid
            val principal = try {
                lookup.lookupPrincipalByName(name)
            } catch (e: Exception) {
                throw WinApiException("cannot resolve principal for SID ${a.sid}", cause = e)
            }
            AclEntry.newBuilder()
                .setType(if (a.allow) AclEntryType.ALLOW else AclEntryType.DENY)
                .setPrincipal(principal)
                .setPermissions(AclRights.permissionsFor(a.rights))
                .apply { if (a.inherit) setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT) }
                .build()
        }
        v.acl = entries
    }
}
