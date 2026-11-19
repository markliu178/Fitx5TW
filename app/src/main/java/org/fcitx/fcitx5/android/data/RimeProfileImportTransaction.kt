/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import org.fcitx.directrime.ProfileArchive
import java.io.File

/**
 * Filesystem half of a profile import.
 *
 * Registration and engine restart stay with the Direct Rime owner. The owner marks the
 * transaction registered immediately after publishing the new profile in its directory config;
 * failures before that point remove the otherwise unreachable imported directory.
 */
internal class RimeProfileImportTransaction private constructor(
    private val archive: File,
    private val staging: File,
    val target: File,
    val name: String,
) {
    private var installed = false
    private var registered = false

    fun install(): File {
        check(!installed) { "方案已经安装" }
        try {
            archive.inputStream().use { ProfileArchive.extract(it, staging) }
            check(staging.renameTo(target)) { "无法安装方案文件" }
            installed = true
            return target
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun markRegistered() {
        check(installed) { "方案尚未安装" }
        registered = true
    }

    fun recoverAfterFailure() {
        staging.deleteRecursively()
        if (installed && !registered) target.deleteRecursively()
    }

    companion object {
        fun prepare(
            archivePath: String,
            archiveDirectory: File,
            parentDirectory: File,
            registeredNames: Set<String>,
            timestamp: Long = System.currentTimeMillis(),
            preferredName: String? = null,
        ): RimeProfileImportTransaction {
            val archive = File(archivePath).canonicalFile
            require(archive.parentFile == archiveDirectory.canonicalFile) { "无效导入路径" }
            val parent = parentDirectory.canonicalFile
            require(parent.isDirectory && parent.canRead() && parent.canWrite()) { "无法写入所选目录" }

            val name = preferredName?.trim()?.takeIf(String::isNotEmpty)
            if (name == null) {
                var suffix = timestamp
                var autoName = "import-$suffix"
                while (autoName in registeredNames || File(parent, autoName).exists() ||
                    File(parent, ".$autoName.importing").exists()) {
                    autoName = "import-${++suffix}"
                }
                return RimeProfileImportTransaction(
                    archive,
                    File(parent, ".$autoName.importing"),
                    File(parent, autoName),
                    autoName,
                )
            }
            require(RimeDataDirectoryState.isValidName(name)) { "方案名称无效：$name" }
            require(name !in registeredNames && !File(parent, name).exists() &&
                !File(parent, ".$name.importing").exists()) {
                "方案名称已存在，请换一个：$name"
            }
            return RimeProfileImportTransaction(
                archive,
                File(parent, ".$name.importing"),
                File(parent, name),
                name,
            )
        }
    }
}
