package org.futo.inputmethod.engine.fcitx5

import android.util.Log
import java.io.File

/**
 * One candidate produced by the fcitx5 (libime) pinyin engine.
 *
 * @param word the Chinese text that would be committed.
 * @param comment optional annotation (usually the pinyin spelling).
 */
data class Fcitx5Candidate(
    val word: String,
    val comment: String
)

/**
 * Thin JNI bridge to the pinyin engine shipped by fcitx5-android.
 *
 * fcitx5-android implements pinyin on top of `libime`: a shared
 * `libime::PinyinIME` (system dictionary + user language model) drives a per-composition
 * `libime::PinyinContext`. This class exposes exactly that model to Kotlin so it can be
 * driven from an [org.futo.inputmethod.engine.IMEInterface].
 *
 * Each instance owns its own [PinyinContext], so multiple instances are independent.
 * The heavy pinyin data (the `sc.dict` system dictionary and the `zh_CN.lm` language model)
 * is loaded once per engine via [init].
 *
 * The native library is loaded lazily; if it is not bundled (for example in builds that do
 * not include the fcitx5 native modules) [isAvailable] returns `false` and construction
 * throws [Fcitx5PinyinUnavailableException], which callers should treat as "engine
 * unavailable" rather than a crash.
 */
class Fcitx5Pinyin(
    /** Directory containing the read-only pinyin data files (`sc.dict`, `zh_CN.lm`, ...). */
    private val dataDir: File,
    /** Writable directory used to persist the user dictionary/history. */
    private val userDir: File,
    /** Enable/disable the user language model (personalized learning). */
    private val enableLearning: Boolean = true
) {
    private external fun nativeCreate(
        dictPath: String,
        lmPath: String,
        userDir: String,
        userDictPath: String,
        userHistoryPath: String
    ): Long

    private external fun nativeDestroy(handle: Long)

    private external fun nativeReset(handle: Long)

    private external fun nativeSetInput(handle: Long, input: String): String

    private external fun nativeCandidates(handle: Long): Array<String>

    private external fun nativeSelect(handle: Long, index: Int): Boolean

    private external fun nativeSentence(handle: Long): String

    /** Learns the current selection, returns the committed text and clears the composition. */
    private external fun nativeCommit(handle: Long): String

    private external fun nativeSelectedLength(handle: Long): Int

    private external fun nativeClearSelection(handle: Long)

    private external fun nativeSave(handle: Long)

    private var handle: Long = 0L

    init {
        if (!isAvailable) {
            throw Fcitx5PinyinUnavailableException(
                loadError?.message ?: "fcitx5 pinyin native library is unavailable",
                loadError
            )
        }

        val dict = File(dataDir, SYSTEM_DICT_NAME)
        val lm = File(dataDir, LANGUAGE_MODEL_NAME)
        if (!dict.exists() || !lm.exists()) {
            throw Fcitx5PinyinUnavailableException(
                "Missing pinyin data files (${dict.absolutePath}, ${lm.absolutePath})"
            )
        }

        userDir.mkdirs()
        val userDict = File(userDir, USER_DICT_NAME)
        val userHistory = File(userDir, USER_HISTORY_NAME)

        handle = nativeCreate(
            dict.absolutePath,
            lm.absolutePath,
            userDir.absolutePath,
            userDict.absolutePath,
            userHistory.absolutePath
        )

        if (handle == 0L) {
            throw Fcitx5PinyinUnavailableException("Failed to initialize fcitx5 pinyin engine")
        }
    }

    /** Feeds [pinyin] (raw ASCII pinyin) into the engine and returns the updated preedit. */
    fun setInput(pinyin: String): String =
        if (handle == 0L) "" else nativeSetInput(handle, pinyin)

    /** The current candidate list, in display order. */
    fun candidates(): List<Fcitx5Candidate> {
        if (handle == 0L) return emptyList()
        return nativeCandidates(handle).map { packed ->
            val sep = packed.indexOf(SEPARATOR)
            if (sep < 0) {
                Fcitx5Candidate(packed, "")
            } else {
                Fcitx5Candidate(packed.substring(0, sep), packed.substring(sep + 1))
            }
        }
    }

    /** Selects the candidate at [index], extending the selected sentence. */
    fun select(index: Int): Boolean =
        handle != 0L && nativeSelect(handle, index)

    /** The current full sentence (selected part + best remaining candidate). */
    fun sentence(): String =
        if (handle == 0L) "" else nativeSentence(handle)

    /**
     * Commits the current sentence: the selected part is learned into the user model,
     * the returned text is what should be sent to the editor, and the composition is cleared.
     */
    fun commit(): String =
        if (handle == 0L) "" else nativeCommit(handle)

    /** Length (in UTF-16 chars) of the already-selected part of the sentence. */
    fun selectedLength(): Int =
        if (handle == 0L) 0 else nativeSelectedLength(handle)

    /** Clears the current composition without committing anything. */
    fun reset() {
        if (handle != 0L) nativeReset(handle)
    }

    /** Discards the current candidate selection, keeping the raw input. */
    fun clearSelection() {
        if (handle != 0L) nativeClearSelection(handle)
    }

    /** Persists the user dictionary/history to disk. */
    fun save() {
        if (handle != 0L) nativeSave(handle)
    }

    fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    companion object {
        private const val TAG = "Fcitx5Pinyin"

        private const val SEPARATOR = '\u0001'

        const val SYSTEM_DICT_NAME = "sc.dict"
        const val LANGUAGE_MODEL_NAME = "zh_CN.lm"
        const val USER_DICT_NAME = "user.dict"
        const val USER_HISTORY_NAME = "user.history"

        private const val LIBRARY_NAME = "fcitx5pinyin"

        private var loadError: Throwable? = null

        /** Whether the fcitx5 pinyin native library could be loaded. */
        val isAvailable: Boolean = try {
            System.loadLibrary(LIBRARY_NAME)
            true
        } catch (t: Throwable) {
            loadError = t
            Log.w(TAG, "fcitx5 pinyin native library is unavailable", t)
            false
        }
    }
}

class Fcitx5PinyinUnavailableException(message: String?, cause: Throwable? = null) :
    IllegalStateException(message, cause)
