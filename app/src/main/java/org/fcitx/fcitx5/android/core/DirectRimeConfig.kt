/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.content.Context
import android.os.Build
import android.util.Log
import org.fcitx.fcitx5.android.data.RimeDataDirectoryState
import org.fcitx.fcitx5.android.data.RimeProfileRestorePlan
import org.fcitx.fcitx5.android.data.WanxiangUpdateTransaction
import org.fcitx.fcitx5.android.utils.FileUtil
import org.fcitx.fcitx5.android.utils.Ini
import java.io.File
import java.io.IOException
import java.util.UUID

/** Uses the existing backup/provider paths, including external directory registrations. */
internal class DirectRimeConfig(private val context: Context) {
    val root: File = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && context.isDeviceProtectedStorage) {
        // External app storage remains credential-protected even when obtained from a DE
        // Context. Before first unlock use a separate, empty profile beside the DE prefs.
        context.filesDir
    } else context.getExternalFilesDir(null) ?: context.filesDir
    val dataRoot = File(root, "rime")
    private val directoryFile = File(root, "config/conf/rime-data-dir.conf")
    private val optionsFile = File(root, "config/conf/rime.conf")
    private val directoryMoves = DirectoryMoveJournal(dataRoot, directoryFile)
    var current: String? = null
        private set
    private val profiles = linkedMapOf<String, File>()
    val profileNames: Set<String> get() = profiles.keys.toSet()
    var error = ""
    var preeditMode = "No"
        private set
    val user: File? get() = current?.let { profiles[it] }

    fun load() {
        org.fcitx.fcitx5.android.data.UserDataRestoreRuntime.requirePathReady(context, root)
        dataRoot.mkdirs()
        directoryMoves.recover()
        purgeDeletedResidues()
        profiles.clear()
        val saved = Ini.parseIniFromFile(directoryFile)?.core
        val names = saved?.findByName("Profiles")?.value.orEmpty().lines()
            .filter(RimeDataDirectoryState::isValidName).distinct()
        val external = saved?.findByName("ExternalPathsV2")?.value?.takeIf { it.isNotBlank() }
            ?.let { RimeDataDirectoryState.decodePaths(it, "", names) }
            ?: saved?.findByName("ExternalPaths")?.value?.takeIf { it.isNotBlank() }
                ?.let { RimeDataDirectoryState.decodePaths(null, it, names) }.orEmpty()
        names.forEach { profiles[it] = external[it]?.let(::File) ?: File(dataRoot, it) }
        // Filter out profiles whose physical directories no longer exist (user deleted them
        // outside the app, e.g. via a file manager).
        profiles.entries.removeAll { (_, path) ->
            runCatching { !path.exists() }.getOrDefault(true)
        }
        // Profiles copied into data/ outside the ZIP import flow are valid schemes too.
        refreshProfiles()
        current = saved?.findByName("Current")?.value?.takeIf { it in profiles }
            ?: profiles.keys.firstOrNull()
        // Keep legacy registrations available for recovery, but destructive operations below
        // refuse to touch any directory containing another registered profile.
        error = if (profiles.values.toList().let { paths -> paths.indices.any { i ->
            paths.take(i).any { RimeProfileRestorePlan.overlap(it.canonicalFile, paths[i].canonicalFile) }
        } }) "方案目录存在包含关系，请改用互不重叠的目录" else ""
        val selected = current
        if (selected != null) {
            val userDir = profiles[selected] ?: return
            org.fcitx.fcitx5.android.data.UserDataRestoreRuntime.requirePathReady(context, userDir)
            userDir.mkdirs()
            // Copied-in files may belong to another UID; take them over before librime opens
            // the profile, otherwise deployment and dictionary sync fail on write access.
            // Keep startup alive and surface the reason in the directory manager instead.
            runCatching {
                repairCopiedProfile(userDir)
                normalizeSyncDirectory(userDir)
            }.onFailure { error = it.message ?: "无法准备方案目录：${userDir.path}" }
            // Finish an interrupted resource update before librime can open the profile.
            WanxiangUpdateTransaction.recover(userDir)
        }
        preeditMode = Ini.parseIniFromFile(optionsFile)?.get("PreeditMode")?.value
            ?.takeIf { it in listOf("No", "ComposingText", "CommitPreview") } ?: "No"
        migratePrototype()
    }

    private fun migratePrototype() {
        val old = File(context.filesDir, "rime/user")
        val marker = File(root, "config/direct-rime-frontend-migrated")
        if (!marker.exists() && old.isDirectory) {
            val target = user ?: return
            // Only copy into an empty profile; never overwrite a deployed frontend profile.
            if (target.list().isNullOrEmpty()) {
                val staging = File.createTempFile(".prototype-", ".import", dataRoot)
                check(staging.delete())
                check(old.copyRecursively(staging)) { "无法迁移实验版用户数据" }
                check(target.delete() && staging.renameTo(target)) { "无法安装迁移的用户数据" }
            }
            marker.parentFile!!.mkdirs()
            FileUtil.writeAtomically(marker, "1\n")
        }
    }

    fun directoryState(): RawConfig {
        refreshProfiles()
        return RawConfig(arrayOf(
            RawConfig("Current", current.orEmpty()), RawConfig("Profiles", profiles.keys.joinToString("\n")),
            RawConfig("PathsV2", profiles.entries.joinToString("\n") { "${hex(it.key)}|${hex(it.value.absolutePath)}" }),
            RawConfig("Error", error)
        ))
    }

    /** Adds scheme directories copied into [dataRoot] directly, keeping external paths intact. */
    private fun refreshProfiles() {
        // A profile folder renamed or deleted outside the app must not stay registered;
        // otherwise it shows as a stale entry (and becomes undeletable) while its renamed
        // folder is discovered as a second profile.
        profiles.entries.removeAll { (name, file) ->
            isInternal(name, file) && runCatching { !file.exists() }.getOrDefault(false)
        }
        // A stale sync_dir used to recreate the old profile folder as rime/<old>/sync. That
        // residue was never a scheme, so drop it instead of registering it again.
        profiles.entries.removeAll { (name, file) -> isInternal(name, file) && removeSyncResidue(file) }
        if (current != null && current !in profiles) current = profiles.keys.firstOrNull()
        val known = profiles.values.map { it.canonicalFile }
        dataRoot.listFiles().orEmpty().forEach { file ->
            val name = file.name
            if (!file.isDirectory || name.startsWith(".") || name in profiles ||
                !RimeDataDirectoryState.isValidName(name)) return@forEach
            if (removeSyncResidue(file)) return@forEach
            val canonical = file.canonicalFile
            if (known.none { RimeProfileRestorePlan.overlap(it, canonical) }) profiles[name] = file
        }
        if (current == null) current = profiles.keys.firstOrNull()
    }

    /**
     * A directory that only holds a sync folder is left over from a renamed profile. Deployment
     * always writes other files, so it cannot be a usable scheme; delete it and ignore it.
     */
    private fun removeSyncResidue(file: File): Boolean {
        val entries = runCatching { file.listFiles()?.toList() }.getOrNull() ?: return false
        if (entries.size != 1 || entries[0].name != "sync" || !entries[0].isDirectory) return false
        runCatching { file.deleteRecursively() }
        return true
    }

    private fun isInternal(name: String, file: File): Boolean = runCatching {
        file.canonicalFile == File(dataRoot, name).canonicalFile
    }.getOrDefault(false)

    /**
     * librime persists an absolute sync_dir in installation.yaml. Profiles moved from data/
     * to rime/ (or imported with a copied installation.yaml) keep syncing into a directory
     * that no longer exists or that this app cannot write, which makes every deployment and
     * dictionary sync fail. Usable user sync directories are kept; anything else is pointed
     * at the profile's own sync/ directory, migrating readable snapshots first.
     */
    private fun normalizeSyncDirectory(profile: File) {
        val file = File(profile, "installation.yaml")
        if (!file.isFile || file.length() > 64 * 1024) return
        if (!file.canWrite()) takeOverFile(file)
        val lines = runCatching { file.readLines().toMutableList() }.getOrNull() ?: return
        val index = lines.indexOfFirst { it.startsWith("sync_dir:") }
        if (index < 0) return
        val recordedText = lines[index].substringAfter(':').trim().trim('"', '\'')
        val recorded = runCatching { File(recordedText).canonicalFile }.getOrNull() ?: return
        val desired = runCatching { File(profile, "sync").canonicalFile }.getOrNull() ?: return
        if (recorded == desired) return
        val appRoot = runCatching { root.canonicalFile }.getOrNull() ?: return
        val insideApp = recorded.path.startsWith(appRoot.path + File.separator)
        // A user sync directory that this app can still write is intentional; keep it.
        if (!insideApp && canUseAsSyncDirectory(recorded)) return
        migrateSyncData(recorded, desired)
        lines[index] = "sync_dir: ${desired.path}"
        runCatching { FileUtil.writeAtomically(file, lines.joinToString("\n", postfix = "\n")) }
    }

    /** Whether librime can create files in [dir]; a missing directory needs a writable parent. */
    private fun canUseAsSyncDirectory(dir: File): Boolean {
        var existing = dir
        while (!existing.exists() && existing.parentFile != null) existing = existing.parentFile!!
        return existing.isDirectory && existing.canRead() && existing.canWrite() &&
            !hasUnwritableEntries(existing, 0)
    }

    /** Copy readable sync snapshots so a rewritten sync_dir keeps the imported dictionaries. */
    internal fun migrateSyncData(from: File, to: File) {
        val sourceRoot = runCatching { from.canonicalFile }.getOrNull() ?: return
        if (sourceRoot == to.canonicalFile || !sourceRoot.isDirectory || !sourceRoot.canRead()) return
        check(to.exists() || to.mkdirs()) { "无法创建方案同步目录：${to.path}" }
        fun copy(src: File, dst: File, depth: Int) {
            // Hidden entries are locks and temporary merge databases, never dictionary data.
            if (depth > 8 || !src.canRead() || src.name.startsWith(".")) return
            if (dst.exists()) return
            if (src.isDirectory) {
                if (!dst.mkdirs()) return
                src.listFiles().orEmpty().forEach { copy(it, File(dst, it.name), depth + 1) }
            } else if (src.isFile) {
                runCatching { src.copyTo(dst, overwrite = false) }
            }
        }
        sourceRoot.listFiles().orEmpty().forEach { copy(it, File(to, it.name), 0) }
    }

    /**
     * Files copied in through Shizuku/root keep another UID, so the app can read but not write
     * them. Replace them with app-owned copies before librime needs write access.
     */
    private fun repairCopiedProfile(profile: File) {
        if (!profile.canWrite()) {
            if (isInternal(profile.name, profile)) {
                throw IllegalStateException(
                    "方案目录“${profile.name}”不可写，可能是以 root/Shizuku 复制进来的；请在文件管理器里修改权限，或重新导入方案"
                )
            }
            return
        }
        profile.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".yaml") && !it.canWrite() }
            .forEach(::takeOverFile)
        profile.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.endsWith(".userdb") && hasUnwritableEntries(it, 0) }
            .forEach(::takeOverDirectory)
        discardForeignBuild(profile)
        // A failed snapshot merge can leave a temporary database behind; its stale lock makes
        // every later sync fail, so drop it and let librime recreate it.
        File(profile, ".temp.userdb").takeIf { it.isDirectory }?.let {
            FileUtil.removeFile(it).onFailure { cause -> Log.w(TAG, "无法清理临时词典：${it.path}", cause) }
        }
        val sync = File(profile, "sync")
        if (sync.isDirectory && hasUnwritableEntries(sync, 0)) takeOverDirectory(sync)
    }

    /** Replace one foreign-owned file with an app-owned copy, keeping its content. */
    private fun takeOverFile(file: File) {
        if (file.canWrite()) return
        require(file.isFile && file.canRead()) { "无法读取方案文件：${file.path}" }
        val temp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.takeover")
        try {
            check(runCatching { file.copyTo(temp, overwrite = true) }.isSuccess) {
                "无法复制方案文件：${file.path}"
            }
            check(file.renameTo(File(file.path + ".stale-${UUID.randomUUID()}"))) {
                "无法替换方案文件：${file.path}"
            }
            check(temp.renameTo(file)) { "无法安装方案文件：${file.path}" }
            Log.w(TAG, "已接管复制进来的方案文件：${file.path}")
        } finally {
            if (temp.exists()) FileUtil.removeFile(temp)
        }
    }

    /** Same as [takeOverFile] for a directory (typically a copied LevelDB user dictionary). */
    private fun takeOverDirectory(dir: File) {
        if (dir.canWrite()) return
        if (!dir.canRead()) {
            throw IllegalStateException(
                "无法读取方案目录：${dir.path}；可能是以 root/Shizuku 复制进来的，请在文件管理器里修改权限后重试"
            )
        }
        val temp = File(dir.parentFile, ".${dir.name}.${UUID.randomUUID()}.takeover")
        try {
            copyDirectory(dir, temp)
            val stale = File(dir.parentFile, "${dir.name}.stale-${UUID.randomUUID()}")
            check(dir.renameTo(stale)) { "无法替换方案目录：${dir.path}" }
            if (!temp.renameTo(dir)) {
                stale.renameTo(dir)
                throw IllegalStateException("无法安装方案目录：${dir.path}")
            }
            FileUtil.removeFile(stale).onFailure {
                Log.w(TAG, "无法清理旧的方案目录副本：${stale.path}", it)
            }
            Log.w(TAG, "已接管复制进来的方案目录：${dir.path}")
        } finally {
            if (temp.exists()) FileUtil.removeFile(temp)
        }
    }

    private fun copyDirectory(src: File, dst: File) {
        val root = runCatching { src.canonicalFile }.getOrNull()
            ?: throw IllegalStateException("无法读取方案目录：${src.path}")
        check(dst.mkdirs()) { "无法创建临时方案目录：${dst.path}" }
        fun copy(from: File, to: File) {
            if (!from.canRead()) throw IllegalStateException("无法读取方案文件：${from.path}")
            val canonical = runCatching { from.canonicalFile }.getOrNull()
            if (canonical == null || (canonical != root &&
                    !canonical.path.startsWith(root.path + File.separator))) {
                throw IllegalStateException("方案目录中包含符号链接，无法接管：${from.path}")
            }
            if (from.isDirectory) {
                check(to.isDirectory || to.mkdirs()) { "无法创建临时方案目录：${to.path}" }
                val children = from.listFiles()
                    ?: throw IllegalStateException("无法读取方案目录：${from.path}")
                children.forEach { copy(it, File(to, it.name)) }
            } else if (from.isFile) {
                check(runCatching { from.copyTo(to, overwrite = false) }.isSuccess) {
                    "无法复制方案文件：${from.path}"
                }
            }
        }
        copy(src, dst)
    }

    /**
     * A build/ directory copied from another device may reference missing schemas and may be
     * partly unwritable. It is always reproducible, so move it out of the way and rebuild.
     */
    private fun discardForeignBuild(profile: File) {
        val build = File(profile, "build")
        if (!build.isDirectory || !hasUnwritableEntries(build, 0)) return
        val stale = File(profile, ".build-${UUID.randomUUID()}")
        if (build.renameTo(stale)) {
            FileUtil.removeFile(stale).onFailure {
                Log.w(TAG, "无法清理复制进来的 build 目录：${stale.path}", it)
            }
            Log.w(TAG, "已丢弃复制进来的 build 目录：${profile.path}")
        }
    }

    /** True when [dir] or a shallow descendant cannot be written by this app. */
    private fun hasUnwritableEntries(dir: File, depth: Int): Boolean {
        if (!dir.isDirectory || !dir.canRead() || !dir.canWrite() || !dir.canExecute()) return true
        if (depth >= 2) return false
        for (entry in dir.listFiles().orEmpty()) {
            if (entry.isFile && !entry.canWrite()) return true
            if (entry.isDirectory && hasUnwritableEntries(entry, depth + 1)) return true
        }
        return false
    }

    /** Trash left by a delete whose cleanup failed; permissions may have been fixed since. */
    private fun purgeDeletedResidues() {
        dataRoot.listFiles().orEmpty().forEach { file ->
            if (!file.isDirectory || !file.name.startsWith(".deleted-")) return@forEach
            var result = FileUtil.removeFile(file)
            if (result.isFailure && hasUnwritableEntries(file, 0)) {
                // Take over files copied in with another UID, then retry the trash removal.
                runCatching { repairCopiedProfile(file) }
                result = FileUtil.removeFile(file)
            }
            result.onFailure {
                Log.w(TAG, "无法清理已删除方案的残留目录：${file.path}", it)
            }
        }
    }

    fun requireUpdateTarget(name: String, path: String) {
        require(error.isBlank()) { error }
        val activeDir = user
        require(current == name && activeDir != null && activeDir.canonicalPath == path) {
            "当前 Rime 工作目录已改变，请返回万象更新页面重新开始"
        }
        org.fcitx.fcitx5.android.data.UserDataRestoreRuntime.requirePathReady(context, activeDir)
        require(activeDir.isDirectory && activeDir.canRead() && activeDir.canWrite()) { "无法读写当前 Rime 工作目录" }
    }

    /** External native code may only start against an existing, non-overlapping writable profile. */
    fun requireExternalEngineProfile(name: String) {
        require(error.isBlank()) { error }
        val target = profiles[name] ?: throw IllegalArgumentException("引擎绑定的方案工作目录不存在：$name")
        org.fcitx.fcitx5.android.data.UserDataRestoreRuntime.requirePathReady(context, target)
        require(target.isDirectory && target.canRead() && target.canWrite()) {
            "无法读写引擎绑定的方案工作目录：$name"
        }
        WanxiangUpdateTransaction.recover(target)
    }

    fun saveDirectories() = persistDirectories(profiles, current)

    private fun persistDirectories(next: Map<String, File>, selected: String?) {
        directoryFile.parentFile!!.mkdirs()
        FileUtil.writeAtomically(directoryFile, directoriesText(next, selected))
    }

    private fun directoriesText(next: Map<String, File>, selected: String?): String {
        val external = next.filter { (name, file) -> !isInternal(name, file) }
            .mapValues { it.value.absolutePath }
        val state = RimeDataDirectoryState(selected.orEmpty(), next.keys.toList(), next.mapValues { it.value.absolutePath })
        val portable = state.portableConfig().substringBefore("ExternalPaths=")
        return portable + RimeDataDirectoryState.externalPathConfig(external)
    }

    private fun publishDirectories(next: Map<String, File>, selected: String?) {
        profiles.clear()
        profiles.putAll(next)
        current = selected
    }

    private fun requireSeparatePath(file: File, excluding: String? = null) {
        val target = file.canonicalFile
        require(profiles.none { (name, existing) -> name != excluding &&
            RimeProfileRestorePlan.overlap(existing.canonicalFile, target) }) {
            "方案目录不能相同或互相包含"
        }
    }

    private fun requireNoNestedProfiles(name: String, file: File) {
        val target = file.canonicalFile
        require(profiles.none { (other, path) -> other != name &&
            RimeProfileRestorePlan.overlap(target, path.canonicalFile) }) {
            "此目录与其他方案重叠，不能删除或移动；请先重新配置方案目录"
        }
    }

    fun create(name: String, path: String = "") {
        require(RimeDataDirectoryState.isValidName(name) && name !in profiles) { "目录名称无效或已存在" }
        val file = if (path.isEmpty()) File(dataRoot, name) else {
            require((path.startsWith('/') || File(path).isAbsolute) && '\u0000' !in path) { "无效的外部目录" }
            File(path).let { if (isInternal(name, it)) File(dataRoot, name) else it }
        }
        requireSeparatePath(file)
        org.fcitx.fcitx5.android.data.UserDataRestoreRuntime.requirePathReady(context, file)
        val existed = file.exists()
        check(file.isDirectory || file.mkdirs()) { "无法创建目录，请检查存储权限" }
        check(file.canRead() && file.canWrite()) { "无法访问目录，请检查存储权限" }
        val next = LinkedHashMap(profiles).apply { put(name, file) }
        // The first profile created becomes the working directory; the engine starts idle otherwise.
        val selected = current ?: name
        try { persistDirectories(next, selected) }
        catch (e: Exception) { if (!existed) file.delete(); throw e }
        publishDirectories(next, selected)
    }

    fun switch(name: String) {
        require(name in profiles) { "目录不存在" }
        val target = profiles.getValue(name)
        org.fcitx.fcitx5.android.data.UserDataRestoreRuntime.requirePathReady(context, target)
        check(target.isDirectory || target.mkdirs()) { "无法打开目录" }
        check(target.canRead() && target.canWrite()) { "无法访问目录，请检查存储权限" }
        repairCopiedProfile(target)
        normalizeSyncDirectory(target)
        WanxiangUpdateTransaction.recover(target)
        persistDirectories(profiles, name)
        current = name
    }

    /** Sync must never follow an absolute sync_dir that a rename made stale. */
    fun normalizeActiveSyncDirectory() {
        user?.let(::normalizeSyncDirectory)
    }

    fun rename(name: String, newName: String) {
        require(name in profiles && RimeDataDirectoryState.isValidName(newName) && newName !in profiles) { "目录名称无效或已存在" }
        val old = profiles.getValue(name)
        val internal = isInternal(name, old)
        val target = if (internal) File(dataRoot, newName) else old
        org.fcitx.fcitx5.android.data.UserDataRestoreRuntime.requirePathReady(context, old)
        org.fcitx.fcitx5.android.data.UserDataRestoreRuntime.requirePathReady(context, target)
        if (internal) {
            requireNoNestedProfiles(name, old)
            requireSeparatePath(target, excluding = name)
        }
        val next = LinkedHashMap(profiles).apply { remove(name); put(newName, target) }
        val selected = if (current == name) newName else current
        if (internal) directoryMoves.prepare(name, newName, false, directoriesText(next, selected))
        try { persistDirectories(next, selected) }
        catch (e: Exception) {
            if (internal) try { directoryMoves.recover() } catch (recovery: Exception) { e.addSuppressed(recovery) }
            throw e
        }
        publishDirectories(next, selected)
        if (internal) directoryMoves.recover()
        // Renaming changes the profile path but librime keeps the old absolute sync_dir in
        // installation.yaml, so the next dictionary sync would recreate the old directory.
        normalizeSyncDirectory(target)
    }

    fun delete(name: String) {
        require(name in profiles) { "目录不存在" }
        val file = profiles.getValue(name)
        org.fcitx.fcitx5.android.data.UserDataRestoreRuntime.requirePathReady(context, file)
        val internal = isInternal(name, file)
        // A folder removed outside the app only needs to be unregistered.
        val moveData = internal && file.exists()
        val next = LinkedHashMap(profiles).apply { remove(name) }
        val nextCurrent = if (current == name) next.keys.firstOrNull() else current
        if (moveData) {
            requireNoNestedProfiles(name, file)
            // Foreign-owned files cannot be unlinked; take them over so the profile is really
            // removed instead of leaving a .deleted-* residue that the user still sees.
            if (hasUnwritableEntries(file, 0)) repairCopiedProfile(file)
            directoryMoves.prepare(name, ".deleted-${UUID.randomUUID()}", true, directoriesText(next, nextCurrent))
        }
        try { persistDirectories(next, nextCurrent) }
        catch (e: Exception) {
            if (moveData) try { directoryMoves.recover() } catch (recovery: Exception) { e.addSuppressed(recovery) }
            throw e
        }
        publishDirectories(next, nextCurrent)
        // External directories are only unregistered; native or user data is never recursively followed through links.
        if (moveData) directoryMoves.recover()
    }

    fun setOptions(config: RawConfig) {
        config.findByName("PreeditMode")?.value?.let {
            require(it in listOf("No", "ComposingText", "CommitPreview"))
            preeditMode = it
        }
        optionsFile.parentFile!!.mkdirs()
        val old = Ini.parseIniFromFile(optionsFile) ?: Ini(RawConfig())
        old.set("PreeditMode", str = preeditMode)
        Ini.writeIniToFile(old, optionsFile)
    }

    fun describe(): RawConfig {
        fun field(name: String, type: String, title: String, vararg attributes: RawConfig) = RawConfig(name,
            arrayOf(RawConfig("Type", type), RawConfig("Description", title), *attributes))
        fun external(name: String, title: String, path: String) = field(name, "External", title, RawConfig("External", path))
        val options = arrayOf(
            field("PreeditMode", "Enum", "编辑器内预编辑", RawConfig("DefaultValue", "No"),
                RawConfig("Enum", arrayOf(RawConfig("0", "No"), RawConfig("1", "ComposingText"), RawConfig("2", "CommitPreview"))),
                RawConfig("EnumI18n", arrayOf(RawConfig("0", "不显示"), RawConfig("1", "编码"), RawConfig("2", "候选预览")))),
            external("UserDataDir", "用户数据目录", "xdg-open"),
            external("DataDirManager", "数据目录管理", "fcitx://addon-dir-profile/addon/rime/profile-manager?app_proto=2"),
            external("SchemaSelector", "启用的输入方案", "fcitx://multiselect/addon/rime/schema-selector?option=Items&min=1&app_proto=2"),
            external("ImportProfile", "导入 Rime 方案 ZIP", "fcitx://direct-rime/import"),
            external("DeployAction", "重新部署", "fcitx://addon-action/addon/rime/deploy?app_proto=2"),
            external("SynchronizeAction", "同步用户词典", "fcitx://addon-action/addon/rime/sync?app_proto=2")
        )
        return RawConfig(arrayOf(
            RawConfig("cfg", arrayOf(RawConfig("PreeditMode", preeditMode))),
            RawConfig("desc", arrayOf(RawConfig("Rime 直连", options)))
        ))
    }

    private fun hex(value: String) = value.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }

    private companion object {
        const val TAG = "DirectRimeConfig"
    }
}
