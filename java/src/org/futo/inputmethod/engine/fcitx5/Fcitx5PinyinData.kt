package org.futo.inputmethod.engine.fcitx5

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Manages the on-device location of the fcitx5 pinyin data files.
 *
 * The engine needs two read-only data files (the `sc.dict` system dictionary and the
 * `zh_CN.lm` language model). They are shipped as assets under `assets/fcitx5-pinyin/`
 * and extracted once into the app's private files directory. User data (learned words and
 * history) is kept in a separate directory that survives data re-extraction.
 *
 * If the assets are not bundled (builds without the fcitx5 pinyin data) extraction is a
 * no-op and [Fcitx5Pinyin] construction fails with
 * [Fcitx5PinyinUnavailableException], which callers treat as "engine unavailable".
 */
object Fcitx5PinyinData {
    private const val TAG = "Fcitx5PinyinData"

    private const val ASSET_DIR = "fcitx5-pinyin"
    private const val VERSION_FILE = ".asset_version"

    fun getDataDir(context: Context): File = File(context.filesDir, "fcitx5-pinyin")

    fun getUserDir(context: Context): File = File(context.filesDir, "fcitx5-pinyin-user")

    /**
     * Extracts the pinyin data assets into [destDir] if they are not already present.
     *
     * @return true if the data is present (either already extracted or just extracted).
     */
    fun ensureExtracted(context: Context, destDir: File): Boolean {
        destDir.mkdirs()

        val versionFile = File(destDir, VERSION_FILE)
        val alreadyExtracted = versionFile.exists() &&
                versionFile.readText() == assetVersion(context) &&
                File(destDir, Fcitx5Pinyin.SYSTEM_DICT_NAME).exists() &&
                File(destDir, Fcitx5Pinyin.LANGUAGE_MODEL_NAME).exists()
        if(alreadyExtracted) {
            return true
        }

        return try {
            val assetManager = context.assets
            val files = assetManager.list(ASSET_DIR) ?: emptyArray()
            if(files.isEmpty()) {
                Log.w(TAG, "No fcitx5 pinyin data assets bundled")
                return false
            }

            files.forEach { name ->
                val source = "$ASSET_DIR/$name"
                val target = File(destDir, name)
                assetManager.open(source).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }

            versionFile.writeText(assetVersion(context))
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to extract fcitx5 pinyin data assets", t)
            false
        }
    }

    private fun assetVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (t: Throwable) {
        "0"
    }
}
