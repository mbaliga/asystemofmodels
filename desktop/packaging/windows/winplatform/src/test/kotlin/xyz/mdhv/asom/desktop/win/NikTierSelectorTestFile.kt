package xyz.mdhv.asom.desktop.win

import java.nio.file.Files
import xyz.mdhv.asom.desktop.win.acl.AclPlan
import xyz.mdhv.asom.desktop.win.fakes.FakeAcl
import xyz.mdhv.asom.desktop.win.fakes.OWNER_SID
import xyz.mdhv.asom.desktop.win.keys.FileNik
import xyz.mdhv.asom.desktop.win.keys.NikKey

/** A T0 key in a temp directory whose (fake) DACL honours the owner-only plan. Shared by several tests. */
object NikTierSelectorTestFile {
    fun fileKey(): NikKey {
        val dir = Files.createTempDirectory("asom-t0-")
        val plan = AclPlan.userState(OWNER_SID)
        val acl = FakeAcl(OWNER_SID)
        acl.replace(dir, plan.required)
        acl.store[dir.resolve(FileNik.FILE_NAME)] = xyz.mdhv.asom.desktop.win.acl.AclSnapshot(OWNER_SID, plan.required)
        return FileNik.create(dir, null, acl, plan)
    }
}
