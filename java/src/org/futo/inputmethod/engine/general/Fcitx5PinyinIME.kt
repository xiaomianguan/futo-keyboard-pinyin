package org.futo.inputmethod.engine.general

import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.engine.ExpandableSuggestionBarConfiguration
import org.futo.inputmethod.engine.IMEHelper
import org.futo.inputmethod.engine.IMEInterface
import org.futo.inputmethod.engine.fcitx5.Fcitx5Pinyin
import org.futo.inputmethod.engine.fcitx5.Fcitx5PinyinData
import org.futo.inputmethod.event.Event
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.SuggestedWords
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.futo.inputmethod.latin.Subtypes.switchToNextLanguage
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.common.InputPointers
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.suggestions.SuggestionStripViewAccessor
import org.futo.inputmethod.latin.uix.FloatingPreEdit
import org.futo.inputmethod.latin.uix.PreEditListener
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.SettingToggleRaw
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.v2keyboard.KeyboardLayoutSetV2
import java.util.Locale

/**
 * Settings for the fcitx5 (libime) pinyin engine.
 */
object Fcitx5PinyinIMESettings {
    /** Whether the engine learns from what the user commits (user dictionary + history). */
    val LearningSetting = SettingsKey(
        booleanPreferencesKey("Fcitx5Pinyin_learning"),
        true
    )

    /** Whether to show the pinyin spelling as the candidate comment. */
    val ShowPinyinSetting = SettingsKey(
        booleanPreferencesKey("Fcitx5Pinyin_show_pinyin"),
        true
    )

    /** Number of candidates shown per page in the suggestion strip. */
    val PageSizeSetting = SettingsKey(
        stringPreferencesKey("Fcitx5Pinyin_page_size"),
        "9"
    )

    val menu = UserSettingsMenu(
        title = R.string.fcitx5_pinyin_settings_title,
        searchTags = R.string.fcitx5_pinyin_setting_search_tags,
        navPath = "ime/zh-fcitx5", registerNavPath = true,
        settings = listOf(
            UserSetting(R.string.fcitx5_pinyin_setting_learning) {
                val (setting, setSetting) = useDataStore(LearningSetting)
                SettingToggleRaw(
                    stringResource(R.string.fcitx5_pinyin_setting_learning),
                    setting,
                    { setSetting(it) },
                    subtitle = stringResource(R.string.fcitx5_pinyin_setting_learning_description)
                )
            },
            UserSetting(R.string.fcitx5_pinyin_setting_show_pinyin) {
                val (setting, setSetting) = useDataStore(ShowPinyinSetting)
                SettingToggleRaw(
                    stringResource(R.string.fcitx5_pinyin_setting_show_pinyin),
                    setting,
                    { setSetting(it) }
                )
            }
        )
    )
}

/**
 * Chinese (pinyin) input method backed by the fcitx5 / libime pinyin engine.
 *
 * This is a sibling of [ChineseIME] (which is backed by Rime). It is selected automatically
 * when the active subtype carries the fcitx5 pinyin marker (see [Subtypes.isFcitx5PinyinActive])
 * and does not affect any other language.
 */
class Fcitx5PinyinIME(val helper: IMEHelper) : IMEInterface, SuggestionStripViewAccessor, PreEditListener {
    private val TAG = "Fcitx5PinyinIME"
    private val connect get() = helper.getCurrentInputConnection()
    private val coroScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val expandableUiCfg = ExpandableSuggestionBarConfiguration(true, false)
    private val maxBufferLength = 0x100000

    private val loading = mutableStateOf(false)
    private var engine: Fcitx5Pinyin? = null
    private var engineUnavailable = false

    private var rawInput = ""
    private var layoutHint: String? = null
    private var showPinyin = true

    private enum class EditingState(val active: Boolean) {
        NotEditing(false),
        Editing(true),
        Finishing(false)
    }
    private var editingState = EditingState.NotEditing

    override fun getLoadingState(): MutableState<Boolean>? = loading

    private fun ensureEngine() {
        if(engine != null || engineUnavailable) return
        loading.value = true
        coroScope.launch {
            try {
                val learning = helper.context.getSetting(Fcitx5PinyinIMESettings.LearningSetting)
                val dataDir = Fcitx5PinyinData.getDataDir(helper.context)
                val userDir = Fcitx5PinyinData.getUserDir(helper.context)
                val created = withContext(Dispatchers.IO) {
                    Fcitx5PinyinData.ensureExtracted(helper.context, dataDir)
                    Fcitx5Pinyin(dataDir, userDir, learning)
                }
                engine = created
                Log.i(TAG, "fcitx5 pinyin engine initialized")
            } catch (t: Throwable) {
                Log.e(TAG, "fcitx5 pinyin engine is unavailable", t)
                engineUnavailable = true
            } finally {
                loading.value = false
            }
        }
    }

    private fun recomputeComposition() {
        val eng = engine ?: run {
            helper.setPreedit(null)
            return
        }

        if(rawInput.isEmpty()) {
            helper.setPreedit(null)
            helper.updateUiInputState(true)
            setNeutralSuggestionStrip()
            return
        }

        val preedit = eng.setInput(rawInput)
        helper.updateUiInputState(false)
        helper.setPreedit(FloatingPreEdit.build(preedit, this, emptyMap(), rawInput))
        showCandidates(eng)
    }

    private fun showCandidates(eng: Fcitx5Pinyin) {
        val candidates = eng.candidates()
        val infos = candidates.mapIndexed { index, candidate ->
            SuggestedWordInfo(
                candidate.word,
                "",
                Int.MAX_VALUE - index,
                1,
                null,
                SuggestedWordInfo.NOT_AN_INDEX,
                SuggestedWordInfo.NOT_A_CONFIDENCE,
                index,
                if(showPinyin) candidate.comment else null
            )
        }.let { ArrayList(it) }

        // Don't flicker an empty suggest bar while editing
        if(editingState != EditingState.NotEditing && infos.isEmpty()) return

        showSuggestionStrip(
            SuggestedWords(infos, infos, null, false, false, false, 0, 0, 0)
        )
    }

    private fun selectCandidate(index: Int): Boolean {
        val eng = engine ?: return false
        if(!eng.select(index)) return false

        if(eng.candidates().isEmpty()) {
            // Whole composition has been selected: commit it.
            commitComposition()
        } else {
            val preedit = eng.setInput(rawInput)
            helper.setPreedit(FloatingPreEdit.build(preedit, this, emptyMap(), rawInput))
            showCandidates(eng)
        }
        return true
    }

    private fun commitComposition(): Boolean {
        val eng = engine ?: return false
        if(rawInput.isEmpty()) return false

        val text = eng.commit()
        rawInput = ""
        if(text.isNotEmpty()) connect?.commitText(text, 1)
        helper.setPreedit(null)
        helper.updateUiInputState(true)
        setNeutralSuggestionStrip()
        return true
    }

    private fun interruptInput(text: CharSequence? = null) {
        engine?.reset()
        rawInput = ""
        helper.setPreedit(null)
        connect?.finishComposingText()
        if(text != null) connect?.commitText(text, 1)
        setNeutralSuggestionStrip()
    }

    private fun commitChar(codePoint: Int) {
        connect?.commitText(String(Character.toChars(codePoint)), 1)
    }

    private fun handleBackspace() {
        if(rawInput.isNotEmpty()) {
            rawInput = rawInput.dropLast(1)
            engine?.clearSelection()
            recomputeComposition()
        } else {
            sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL, 0)
        }
    }

    private fun handleCodePoint(codePoint: Int) {
        if(engine == null) {
            // Engine not (yet) available: fall back to plain text input so the keyboard
            // is still usable.
            commitChar(codePoint)
            return
        }

        val c = if(codePoint in 'A'.code..'Z'.code) (codePoint + 32).toChar() else codePoint.toChar()

        when {
            c in 'a'..'z' || c == '\'' -> {
                rawInput += c
                recomputeComposition()
            }

            c == ' ' -> {
                if(!commitComposition()) commitChar(codePoint)
            }

            c in '1'..'9' -> {
                if(!(rawInput.isNotEmpty() && selectCandidate(c - '1'))) {
                    commitChar(codePoint)
                }
            }

            else -> {
                commitComposition()
                commitChar(codePoint)
            }
        }
    }

    private fun sendDownUpKeyEvent(keyCode: Int, metaState: Int = 0) {
        val eventTime = android.os.SystemClock.uptimeMillis()
        connect?.sendKeyEvent(
            KeyEvent(
                eventTime, eventTime,
                KeyEvent.ACTION_DOWN, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
        connect?.sendKeyEvent(
            KeyEvent(
                eventTime, eventTime,
                KeyEvent.ACTION_UP, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    private fun triggerIfIsAction(keyCode: Int): Boolean {
        if (Constants.CODE_ACTION_0 <= keyCode && keyCode <= Constants.CODE_ACTION_MAX) {
            helper.triggerAction(keyCode - Constants.CODE_ACTION_0, false)
            return true
        }
        if (Constants.CODE_ALT_ACTION_0 <= keyCode && keyCode <= Constants.CODE_ALT_ACTION_MAX) {
            helper.triggerAction(keyCode - Constants.CODE_ALT_ACTION_0, true)
            return true
        }
        return false
    }

    override fun onEvent(event: Event) {
        helper.requestCursorUpdate()
        when (event.eventType) {
            Event.EVENT_TYPE_INPUT_KEYPRESS,
            Event.EVENT_TYPE_INPUT_KEYPRESS_RESUMED -> {
                if (triggerIfIsAction(event.mKeyCode)) return

                when {
                    event.mKeyCode == Constants.CODE_DELETE -> handleBackspace()
                    event.mKeyCode == Event.NOT_A_KEY_CODE -> handleCodePoint(event.mCodePoint)
                    else -> {
                        interruptInput()
                        sendDownUpKeyEvent(event.mKeyCode, 0)
                    }
                }
            }

            Event.EVENT_TYPE_SUGGESTION_PICKED -> {
                val suggestion = event.mSuggestedWordInfo ?: return
                if (suggestion.isKindOf(SuggestedWordInfo.KIND_UNDO)) {
                    connect?.commitText(suggestion.word, 1)
                } else {
                    selectCandidate(suggestion.mCandidateIndex)
                }
            }

            Event.EVENT_TYPE_SOFTWARE_GENERATED_STRING -> {
                interruptInput(event.mText)
            }

            Event.EVENT_TYPE_DOWN_UP_KEYEVENT -> {
                interruptInput()
                sendDownUpKeyEvent(event.mKeyCode, event.mX)
            }

            else -> {}
        }
    }


    override fun onCreate() {
        // The engine is created lazily in onStartInput/ensureEngine so that creating the
        // IME (which happens on the main thread) never blocks on loading the pinyin data.
    }

    override fun onDestroy() {
        coroScope.cancel()
        engine?.save()
        engine?.close()
        engine = null
    }

    override fun onStartInput() {
        showPinyin = helper.context.getSettingBlocking(Fcitx5PinyinIMESettings.ShowPinyinSetting)
        ensureEngine()
    }

    override fun onLayoutUpdated(layout: KeyboardLayoutSetV2) {
        layoutHint = layout.mainLayout.imeHint
    }

    override fun onFinishInput() {
        if(editingState.active) return
        rawInput = ""
        engine?.reset()
        helper.setPreedit(null)
    }

    override fun onFinishSlidingInput() {}

    // PreEditListener (editing the pinyin buffer via the floating pre-edit)

    override fun onStartEdit() {
        editingState = EditingState.Editing
    }

    private fun safeguardInputStringForSimulation(input: String): String =
        input.filter { it in 'a'..'z' || it in 'A'..'Z' || it == '\'' }

    override fun onUpdateEdit(string: String) {
        val converted = safeguardInputStringForSimulation(string)
        rawInput = converted
        engine?.setInput(rawInput)
        recomputeComposition()
    }

    override fun onFinishEdit(string: String, candidateWord: String?, candidateIndex: Int?) {
        if(!editingState.active) return
        editingState = EditingState.Finishing

        val converted = safeguardInputStringForSimulation(string)
        rawInput = converted
        engine?.setInput(rawInput)

        if(candidateIndex != null) {
            selectCandidate(candidateIndex)
        } else {
            recomputeComposition()
        }

        editingState = EditingState.NotEditing
    }

    // Cursor / delete pointer movement

    private var anchorOutOfDate = true
    private var anchorLeftCount = 0
    private var anchorRightCount = 0
    private var currentMovingCursor = 0

    private val currentAnchorCursor: Pair<Int, Int> get() {
        if (anchorOutOfDate) {
            anchorLeftCount = (connect?.getTextBeforeCursor(maxBufferLength, 0) ?: "").length
            anchorRightCount = (connect?.getTextAfterCursor(maxBufferLength, 0) ?: "").length
            currentMovingCursor = anchorLeftCount
            anchorOutOfDate = false
        }
        return Pair(anchorLeftCount, anchorRightCount)
    }

    override fun onMovePointer(steps: Int, stepOverWords: Boolean, select: Boolean?) {
        var meta = 0
        if (stepOverWords) meta = meta or KeyEvent.META_CTRL_ON
        if (select == true) meta = meta or KeyEvent.META_SHIFT_ON
        if(steps < 0) {
            for(i in 0 until -steps) sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT, meta)
        } else {
            for(i in 0 until steps) sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, meta)
        }
    }

    override fun onUpWithPointerActive() {
        // TODO("Unsupported yet")
    }

    override fun onMoveDeletePointer(steps: Int) {
        setNeutralSuggestionStrip()
        val (lBound, rBound) = currentAnchorCursor
        currentMovingCursor = when {
            steps < 0 -> maxOf(0, currentMovingCursor - 1)
            steps > 0 -> minOf(lBound + rBound, currentMovingCursor + 1)
            else -> currentMovingCursor
        }
        connect?.setSelection(lBound, currentMovingCursor)
    }

    override fun onUpWithDeletePointerActive() {
        anchorOutOfDate = true
        val selection: CharSequence? = connect?.getSelectedText(0)
        if (selection == null) {
            onUpWithPointerActive()
            return
        }
        interruptInput(selection)
    }

    override fun onSwipeLanguage(direction: Int) {
        switchToNextLanguage(helper.context, direction)
    }

    override fun setNeutralSuggestionStrip() {
        helper.setNeutralSuggestionStrip(expandableUiCfg)
    }

    override fun showSuggestionStrip(suggestedWords: SuggestedWords?) {
        helper.showSuggestionStrip(suggestedWords, expandableUiCfg)
    }

    override fun requestSuggestionRefresh() {
        showSuggestionStrip(null)
    }

    override fun isGestureHandlingAvailable(): Boolean = false
    override fun onCustomRequest(requestCode: Int): Boolean = false
    override fun onMovingCursorLockEvent(canMoveCursor: Boolean) {}

    override fun clearUserHistoryDictionaries() {
        Log.d(TAG, "Clearing fcitx5 pinyin user dictionary...")
        coroScope.launch {
            withContext(Dispatchers.IO) {
                engine?.close()
                engine = null
                Fcitx5PinyinData.getUserDir(helper.context).deleteRecursively()
            }
            engineUnavailable = false
            ensureEngine()
        }
    }

    override fun onStartBatchInput() {}
    override fun onUpdateBatchInput(batchPointers: InputPointers?) {}
    override fun onEndBatchInput(batchPointers: InputPointers?) {}
    override fun onCancelBatchInput() {}

    // Non-behavior methods
    override fun onCancelInput() {}
    override fun onDeviceUnlocked() {}
    override fun onOrientationChanged() {}
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, composingSpanStart: Int, composingSpanEnd: Int) {}
}

