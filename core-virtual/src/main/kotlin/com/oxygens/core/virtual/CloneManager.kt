package com.oxygens.core.virtual

import com.oxygens.core.loader.GuestApkParser
import com.oxygens.core.loader.NativeLibraryInstaller
import com.oxygens.core.storage.VirtualStorage
import com.oxygens.core.virtual.model.CloneInfo
import com.oxygens.core.virtual.vpms.VirtualPackageManagerService
import com.oxygens.registry.CloneDao
import com.oxygens.registry.CloneEntity
import java.io.File
import java.util.zip.ZipFile
import kotlin.random.Random

/**
 * User-facing facade (plan diagram: "Clone Manager"). Coordinates parsing a guest
 * APK, extracting it into per-clone storage, registering it with VPMS, and persisting
 * the clone's metadata via clone-registry-db.
 *
 * Process-group assignment (plan §2, process-per-clone): a fixed small number of
 * process groups exist today (":clone_0", ":clone_1" — see StubComponentPool). This
 * caps concurrent distinct clones until the stub pool is generated rather than
 * hand-declared. Surface a clear "clone limit reached" error rather than silently
 * reusing a process group and corrupting another clone's task stack.
 */
class CloneManager(
    private val parser: GuestApkParser,
    private val nativeLibraryInstaller: NativeLibraryInstaller,
    private val storage: VirtualStorage,
    private val vpms: VirtualPackageManagerService,
    private val cloneDao: CloneDao,
    private val availableProcessGroups: List<String>,
) {

    class CloneLimitReachedException(message: String) : Exception(message)

    /**
     * @param apkFile     The APK (or APKS bundle) file to install.
     * @param displayName Override label; if null, falls back to the parsed applicationLabel.
     *                    Pass a real label from PackageManager when available so the tile
     *                    shows the actual app name rather than a resource-reference fallback.
     * @param iconPngBytes PNG bytes of the app icon extracted at the call-site (app module)
     *                    via PackageManager. Stored to VirtualStorage as icon.png. null =
     *                    no icon available (e.g. extraction failed); UI shows a placeholder.
     */
    suspend fun installClone(
        apkFile: File,
        displayName: String?,
        iconPngBytes: ByteArray? = null,
    ): CloneInfo {
        val parsed = parser.parse(apkFile)
        val cloneId = Random.nextInt(1, Int.MAX_VALUE)

        val usedGroups = cloneDao.getAll().map { it.processGroup }.toSet()
        val processGroup = availableProcessGroups.firstOrNull { it !in usedGroups }
            ?: throw CloneLimitReachedException(
                "All ${availableProcessGroups.size} process-group slots are in use. " +
                    "Remove a clone first, or extend the stub pool (manifest + StubComponentPool)."
            )

        val dataDir = storage.filesDir(cloneId, parsed.packageName).parentFile
            ?: error("VirtualStorage returned a files dir with no parent for clone $cloneId")

        // For APKS bundles the original file is a zip-of-APKs, not a valid APK.
        // extractGuestApk/nativeLibraryInstaller both need a real APK (parseable by
        // ZipFile at the APK level), so extract base.apk to a temp file first.
        // The temp lives beside the original; it's deleted in the finally block below.
        val tempBase: File? = if (parsed.isApksBundle) {
            val dest = File(apkFile.parentFile ?: apkFile.absoluteFile.parentFile!!,
                "base_install_${cloneId}.apk")
            ZipFile(apkFile).use { zip ->
                val entry = zip.getEntry("base.apk")
                    ?: error("APKS bundle missing base.apk at install time — parser should have caught this")
                zip.getInputStream(entry).use { inp ->
                    dest.outputStream().use { out -> inp.copyTo(out) }
                }
            }
            dest
        } else null

        val effectiveApk = tempBase ?: apkFile
        val extractedApkPath: String
        try {
            extractedApkPath = storage.extractGuestApk(cloneId, parsed.packageName, effectiveApk)
            nativeLibraryInstaller.installFor(cloneId, parsed.packageName, effectiveApk)
        } finally {
            tempBase?.delete()
        }

        vpms.installPackage(cloneId, parsed)

        // Store the icon PNG next to the extracted APK so the launcher can load it
        // without needing the original picker file (which may have been deleted).
        val iconPath: String? = if (iconPngBytes != null) {
            try {
                val iconFile = storage.iconFile(cloneId, parsed.packageName)
                iconFile.writeBytes(iconPngBytes)
                iconFile.absolutePath
            } catch (e: Exception) {
                null // icon storage failure is non-fatal
            }
        } else null

        val entity = CloneEntity(
            cloneId = cloneId,
            guestPackageName = parsed.packageName,
            displayName = displayName ?: parsed.applicationLabel,
            guestApkPath = extractedApkPath,
            dataDir = dataDir.absolutePath,
            processGroup = processGroup,
            installedAtMillis = System.currentTimeMillis(),
            iconPath = iconPath,
            // Populated from GuestApkParser — the MAIN+LAUNCHER Activity class name, or
            // null when the guest has no launcher entry point (e.g. a library APK).
            mainActivity = parsed.mainActivity,
        )
        cloneDao.insert(entity)

        return entity.toCloneInfo()
    }

    suspend fun removeClone(cloneId: Int) {
        val entity = cloneDao.getById(cloneId) ?: return
        vpms.uninstallPackage(cloneId, entity.guestPackageName)
        storage.wipeClone(cloneId)
        cloneDao.delete(cloneId)
    }

    suspend fun listClones(): List<CloneInfo> = cloneDao.getAll().map { it.toCloneInfo() }

    private fun CloneEntity.toCloneInfo() = CloneInfo(
        cloneId = cloneId,
        guestPackageName = guestPackageName,
        displayName = displayName,
        guestApkPath = guestApkPath,
        dataDir = dataDir,
        processGroup = processGroup,
        installedAtMillis = installedAtMillis,
        iconPath = iconPath,
        mainActivity = mainActivity,
    )
}
