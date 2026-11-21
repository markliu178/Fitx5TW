/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

object ThemePreset {

    val MaterialLight = Theme.Builtin(
        name = "MaterialLight",
        isDark = false,
        backgroundColor = 0xffeceff1,
        barColor = 0xffe4e7e9,
        keyboardColor = 0xffeceff1,
        keyBackgroundColor = 0xfffbfbfc,
        keyTextColor = 0xff37474f,
        candidateTextColor = 0xff37474f,
        candidateLabelColor = 0xff37474f,
        candidateCommentColor = 0xff7a858a,
        altKeyBackgroundColor = 0xffdfe2e4,
        // Google Pinyin's symbol color on alphabet key: #727d82
        altKeyTextColor = 0xff7a858a,
        accentKeyBackgroundColor = 0xff5cb5ab,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x1f000000,
        keyShadowColor = 0xffc0c3c4,
        popupBackgroundColor = 0xffd9dbdd,
        popupTextColor = 0xff37474f,
        spaceBarColor = 0xffc9ced1,
        dividerColor = 0x1f000000,
        clipboardEntryColor = 0xfffbfbfc,
        genericActiveBackgroundColor = 0xff80cbc4,
        genericActiveForegroundColor = 0xff37474f
    )

    val MaterialDark = Theme.Builtin(
        name = "MaterialDark",
        isDark = true,
        backgroundColor = 0xff263238,
        barColor = 0xff21272b,
        keyboardColor = 0xff263238,
        keyBackgroundColor = 0xff404a50,
        keyTextColor = 0xffd9dbdc,
        candidateTextColor = 0xffd9dbdc,
        candidateLabelColor = 0xffd9dbdc,
        candidateCommentColor = 0xffadb1b3,
        altKeyBackgroundColor = 0xff313c42,
        // Google Pinyin's symbol color on alphabet key: #b3b7b9
        altKeyTextColor = 0xffadb1b3,
        accentKeyBackgroundColor = 0xff6eaca8,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x33ffffff,
        keyShadowColor = 0xff1f292e,
        popupBackgroundColor = 0xff3c474c,
        popupTextColor = 0xffffffff,
        spaceBarColor = 0xff3b464c,
        dividerColor = 0x1fffffff,
        clipboardEntryColor = 0xff404a50,
        genericActiveBackgroundColor = 0xff4db6ac,
        genericActiveForegroundColor = 0xffffffff
    )

    val PixelLight = Theme.Builtin(
        name = "PixelLight",
        isDark = false,
        backgroundColor = 0xffeeeeee,
        barColor = 0xffeeeeee,
        keyboardColor = 0xfffafafa,
        keyBackgroundColor = 0xffffffff,
        keyTextColor = 0xff212121,
        candidateTextColor = 0xff212121,
        candidateLabelColor = 0xff212121,
        candidateCommentColor = 0xff6e6e6e,
        altKeyBackgroundColor = 0xffe1e1e1,
        // Google Pinyin's symbol color on alphabet key: #4e4e4e
        altKeyTextColor = 0xff6e6e6e,
        accentKeyBackgroundColor = 0xff4285f4,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x1f000000,
        keyShadowColor = 0xffc2c2c2,
        popupBackgroundColor = 0xffeeeeee,
        popupTextColor = 0xff212121,
        spaceBarColor = 0xffdbdbdb,
        dividerColor = 0x1f000000,
        clipboardEntryColor = 0xffffffff,
        genericActiveBackgroundColor = 0xff5e97f6,
        genericActiveForegroundColor = 0xffffffff
    )

    val PixelDark = Theme.Builtin(
        name = "PixelDark",
        isDark = true,
        backgroundColor = 0xff2d2d2d,
        barColor = 0xff373737,
        keyboardColor = 0xff2d2d2d,
        keyBackgroundColor = 0xff464646,
        keyTextColor = 0xfffafafa,
        candidateTextColor = 0xfffafafa,
        candidateLabelColor = 0xfffafafa,
        candidateCommentColor = 0xffacacac,
        altKeyBackgroundColor = 0xff373737,
        // Google Pinyin's symbol color on alphabet key: #d6d6d6
        altKeyTextColor = 0xffacacac,
        accentKeyBackgroundColor = 0xff5e97f6,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x33ffffff,
        keyShadowColor = 0xff252525,
        popupBackgroundColor = 0xff373737,
        popupTextColor = 0xfffafafa,
        spaceBarColor = 0xff4a4a4a,
        dividerColor = 0x1fffffff,
        clipboardEntryColor = 0xff464646,
        genericActiveBackgroundColor = 0xff5e97f6,
        genericActiveForegroundColor = 0xfffafafa
    )

    /**
     * transparent background with semi-transparent white keys
     */
    val TransparentDark = Theme.Builtin(
        name = "TransparentDark",
        isDark = true,
        backgroundColor = 0xff2d2d2d,
        barColor = 0x4c000000,
        keyboardColor = 0x00000000,
        keyBackgroundColor = 0x4bffffff,
        keyTextColor = 0xffffffff,
        candidateTextColor = 0xffffffff,
        candidateLabelColor = 0xffffffff,
        candidateCommentColor = 0xc9ffffff,
        altKeyBackgroundColor = 0x0cffffff,
        altKeyTextColor = 0xc9ffffff,
        accentKeyBackgroundColor = 0xff5e97f6,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x1f000000,
        keyShadowColor = 0x00000000,
        popupBackgroundColor = 0xff373737,
        popupTextColor = 0xfffafafa,
        spaceBarColor = 0x4bffffff,
        dividerColor = 0x1fffffff,
        clipboardEntryColor = 0x32ffffff,
        genericActiveBackgroundColor = 0xff5e97f6,
        genericActiveForegroundColor = 0xfffafafa
    )

    /**
     * transparent background with semi-transparent black keys
     */
    val TransparentLight = Theme.Builtin(
        name = "TransparentLight",
        isDark = false,
        backgroundColor = 0xffeeeeee,
        barColor = 0x26000000,
        keyboardColor = 0x00000000,
        keyBackgroundColor = 0x4bffffff,
        keyTextColor = 0xff000000,
        candidateTextColor = 0xff000000,
        candidateLabelColor = 0xff000000,
        candidateCommentColor = 0xb9000000,
        altKeyBackgroundColor = 0x0cffffff,
        altKeyTextColor = 0xb9000000,
        accentKeyBackgroundColor = 0xff5e97f6,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x1f000000,
        keyShadowColor = 0x00000000,
        popupBackgroundColor = 0xffeeeeee,
        popupTextColor = 0xff212121,
        spaceBarColor = 0x5affffff,
        dividerColor = 0x1f000000,
        clipboardEntryColor = 0x4bffffff,
        genericActiveBackgroundColor = 0xff5e97f6,
        genericActiveForegroundColor = 0xffffffff
    )

}
