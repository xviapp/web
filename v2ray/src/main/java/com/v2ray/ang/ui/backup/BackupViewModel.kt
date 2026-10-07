package com.v2ray.ang.ui.backup

import android.app.Application
import android.net.Uri
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.ui.base.ViewModelEvent
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.ZipUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class BackupViewModel(application: Application) : BaseViewModel(application) {

    sealed interface BackupViewModelEvent : ViewModelEvent {
        data class ShareFile(val filePath: String) : BackupViewModelEvent
        data class ExportLocal(val cachePath: String, val targetUri: Uri) : BackupViewModelEvent
        object RestoreSuccess : BackupViewModelEvent
    }

    fun cleanupProfileStorage() {
        launchLoading {
            try {
                val removed = withContext(Dispatchers.IO) {
                    MmkvManager.removeOrphanedServerProfiles()
                }
                if (removed == null) {
                    toastError(R.string.toast_profile_storage_cleanup_skipped)
                } else {
                    toastSuccess(getString(R.string.toast_profile_storage_cleanup, removed))
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to clean up profile storage", e)
                toastError(R.string.toast_failure)
            }
        }
    }

    fun shareBackup(cacheDir: File, appName: String) {
        launchLoading {
            val ret = backupConfigurationToCache(cacheDir, appName)
            if (ret.first) {
                _viewModelEvent.send(BackupViewModelEvent.ShareFile(ret.second))
            } else {
                toastError(R.string.toast_failure)
            }
        }
    }

    fun prepareBackupForUri(cacheDir: File, appName: String, targetUri: Uri) {
        launchLoading {
            val ret = backupConfigurationToCache(cacheDir, appName)
            if (ret.first) {
                _viewModelEvent.send(BackupViewModelEvent.ExportLocal(ret.second, targetUri))
            } else {
                toastError(R.string.toast_failure)
            }
        }
    }

    fun restoreConfiguration(cacheDir: File, zipFile: File) {
        launchLoading {
            val success = performRestore(cacheDir, zipFile)
            if (success) {
                toastSuccess(R.string.toast_success)
                _viewModelEvent.send(BackupViewModelEvent.RestoreSuccess)
            } else {
                toastError(R.string.toast_failure)
            }
        }
    }

    private fun backupConfigurationToCache(cacheDir: File, appName: String): Pair<Boolean, String> {
        val dateFormatted = SimpleDateFormat(
            "yyyy-MM-dd-HH-mm-ss",
            Locale.getDefault()
        ).format(System.currentTimeMillis())
        val folderName = "${appName}_$dateFormatted"
        val backupDir = cacheDir.absolutePath + "/$folderName"
        val outputZipFilePath = "${cacheDir.absolutePath}/$folderName.zip"

        val count = MMKV.backupAllToDirectory(backupDir)
        if (count <= 0) {
            return Pair(false, "")
        }

        return if (ZipUtil.zipFromFolder(backupDir, outputZipFilePath)) {
            Pair(true, outputZipFilePath)
        } else {
            Pair(false, "")
        }
    }

    private suspend fun performRestore(cacheDir: File, zipFile: File): Boolean =
        withContext(Dispatchers.IO) {
            val backupDir = File(cacheDir, "restore_${System.nanoTime()}")
            try {
                if (!ZipUtil.unzipToFolder(zipFile, backupDir.absolutePath)) {
                    return@withContext false
                }

                val count = MMKV.restoreAllFromDirectory(backupDir.absolutePath)
                SettingsChangeManager.makeSetupGroupTab()
                SettingsChangeManager.makeRestartService()
                count > 0
            } finally {
                backupDir.deleteRecursively()
            }
        }
}
