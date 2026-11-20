/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.content.Context
import android.os.Build
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
            normalizeSyncDirectory(userDir)
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
        if (current != null && current !in profiles) current = profiles.keys.firstOrNull()
        val known = profiles.values.map { it.canonicalFile }
        dataRoot.listFiles().orEmpty().forEach { file ->
            val name = file.name
            if (!file.isDirectory || name.startsWith(".") || name in profiles ||
                !RimeDataDirectoryState.isValidName(name)) return@forEach
            val canonical = file.canonicalFile
            if (known.none { RimeProfileRestorePlan.overlap(it, canonical) }) profiles[name] = file
        }
        if (current == null) current = profiles.keys.firstOrNull()
    }

    private fun isInternal(name: String, file: File): Boolean = runCatching {
        file.canonicalFile == File(dataRoot, name).canonicalFile
    }.getOrDefault(false)

    /**
     * librime persists an absolute sync_dir in installation.yaml. Profiles moved from data/
     * to rime/ (or imported with a copied installation.yaml) keep syncing into a directory
     * that no longer exists, which makes every dictionary sync fail. Rewrite only locations
     * that live inside our own external files directory; explicit user sync directories stay.
     */
    private fun normalizeSyncDirectory(profile: File) {
        val file = File(profile, "installation.yaml")
        if (!file.isFile || file.length() > 64 * 1024 || !file.canWrite()) return
        val lines = runCatching { file.readLines() }.getOrNull() ?: return
        val index = lines.indexOfFirst { it.startsWith("sync_dir:") }
        if (index < 0) return
        val recorded = runCatching {
            File(lines[index].substringAfter(':').trim().trim('"', '\'')).canonicalFile
        }.getOrNull() ?: return
        val desired = runCatching { File(profile, "sync").canonicalFile }.getOrNull() ?: return
        if (recorded == desired) return
        val appRoot = runCatching { root.canonicalFile }.getOrNull() ?: return
        if (!recorded.path.startsWith(appRoot.path + File.separator)) return
        lines[index] = "sync_dir: ${desired.path}"
        runCatching { FileUtil.writeAtomically(file, lines.joinToString("\n", postfix = "\n")) }
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
        normalizeSyncDirectory(target)
        WanxiangUpdateTransaction.recover(target)
        persistDirectories(profiles, name)
        current = name
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
    }

    fun delete(name: String) {
        require(name in profiles) { "目录不存在" }
        val file = profiles.getValue(name)
        org.fcitx.fcitx5.android.data.UserDataRestoreRuntime.requirePathReady(context, file)
        val internal = isInternal(name, file)
        // A folder removed outside the app only needs to be unregistered.
        val moveData = internal && file.exists()
        val next = LinkedHashMap(profiles).apply { remove(name) }
        if (moveData) {
            requireNoNestedProfiles(name, file)
            directoryMoves.prepare(name, ".deleted-${UUID.randomUUID()}", true, directoriesText(next, current))
        }
        val nextCurrent = if (current == name) next.keys.firstOrNull() else current
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
}
