package org.futo.inputmethod.engine

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.futo.inputmethod.engine.general.ChineseIMESettings
import org.futo.inputmethod.engine.general.Fcitx5PinyinIMESettings
import org.futo.inputmethod.engine.general.JapaneseIMESettings
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.Subtypes
import org.futo.inputmethod.latin.SubtypesSetting
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem

@Composable
private fun isVisible(language: String): Boolean {
    val subtypeSet = useDataStoreValue(SubtypesSetting)
    return remember(subtypeSet) {
        subtypeSet.any {
            Subtypes.getLocale(Subtypes.convertToSubtype(it).locale).language == language
        }
    }
}

/**
 * Visible when the user has a Rime based Chinese subtype enabled. The fcitx5 pinyin subtype
 * shares the "zh" language, so it must be excluded here to keep the two Chinese engine
 * settings menus independent.
 */
@Composable
private fun isRimeChineseVisible(): Boolean {
    val subtypeSet = useDataStoreValue(SubtypesSetting)
    return remember(subtypeSet) {
        subtypeSet.any {
            val subtype = Subtypes.convertToSubtype(it)
            Subtypes.getLocale(subtype).language == "zh" && !Subtypes.isFcitx5Pinyin(subtype)
        }
    }
}

/** Visible when the user has the fcitx5 pinyin subtype enabled. */
@Composable
private fun isFcitx5PinyinVisible(): Boolean {
    val subtypeSet = useDataStoreValue(SubtypesSetting)
    return remember(subtypeSet) {
        subtypeSet.any {
            Subtypes.isFcitx5Pinyin(Subtypes.convertToSubtype(it))
        }
    }
}

val SettingsByLanguage = listOf(
    ChineseIMESettings.menu.copy(visibilityCheck = { isRimeChineseVisible() }),
    Fcitx5PinyinIMESettings.menu.copy(visibilityCheck = { isFcitx5PinyinVisible() }),
    JapaneseIMESettings.menu.copy(visibilityCheck = { isVisible("ja") })
)

@Composable
private fun anyVisible(): Boolean =
    SettingsByLanguage.any { it.visibilityCheck?.invoke() == true }

private val IMESettings = buildList {
    SettingsByLanguage.forEach {
        add(
            userSettingNavigationItem(
                title = it.title,
                style = NavigationItemStyle.HomePrimary,
                icon = R.drawable.globe,
                navigateTo = it.navPath,
            ).copy(
                visibilityCheck = it.visibilityCheck,
                appearInSearchIfVisibilityCheckFailed = false
            )
        )
    }
}

val IMESettingsMenu = UserSettingsMenu(
    title = R.string.language_specific_settings_title,
    navPath = "ime", registerNavPath = true,
    settings = IMESettings, visibilityCheck = { anyVisible() }
)