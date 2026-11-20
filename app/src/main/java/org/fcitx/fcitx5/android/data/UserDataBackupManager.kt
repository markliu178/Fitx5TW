/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.util.UUID

object UserDataBackupManager {
    /** Stop native writers while copying; cancellation must still resume the engine. */
    suspend fun prepare(timestamp: Long = System.currentTimeMillis(),
                        kind: String = UserDataManager.KIND_FULL): File = withContext(NonCancellable) {
        val name = "UserDataBackup-${UUID.randomUUID()}"
        val connection = FcitxDaemon.connect(name)
        var archive: File? = null
        try {
            val state = connection.runOnReady {
                // The dictionary sync only merges userdb text files; the profile files are
                // copied while the engine is stopped, so a failed sync must not block the
                // backup (a broken sync directory used to abort every export/restore).
                try {
                    save()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Unable to synchronize user dictionaries before backup")
                }
                RimeDataDirectoryState.fromConfig(getAddonSubConfig("rime", "profile-manager"),
                    appContext.getExternalFilesDir(null)!!.resolve("rime"))
            }
            val ready = withContext(Dispatchers.IO) {
                UserDataBackupArchive.prepare(appContext.cacheDir,
                    pause = { FcitxDaemon.beginUserDataBackup() },
                    resume = { FcitxDaemon.finishUserDataBackup() }) {
                    UserDataManager.export(it, state, timestamp, kind).getOrThrow()
                }
            }
            archive = ready
            connection.runOnReady { }
            ready
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { archive?.delete() }
            throw e
        } finally { FcitxDaemon.disconnect(name) }
    }
}
