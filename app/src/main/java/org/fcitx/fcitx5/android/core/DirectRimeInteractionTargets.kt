/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import org.fcitx.directrime.RawInputState
import org.fcitx.directrime.Snapshot

/** Current display targets and explicit caret interaction use the backend's live owner state. */
internal class DirectRimeInteractionTargets(private val owner: Owner) {
    /** Reads stay dynamic: inputSession can change from outside the native owner. */
    interface Owner {
        val acceptsInput: Boolean
        val inputSession: Long
        val snapshot: Snapshot?
        val candidateActionContext: CandidateActionContext
        val candidateTabActionContext: CandidateTabActionContext
        val started: Boolean
        val maintenance: Boolean
        val candidateVersion: Long
        val preeditMode: String
        val clientPreeditText: String
        fun rawInput(value: Snapshot): RawInputState?
        fun restoreClientCursor(generation: Long)
        fun moveRawCursor(target: Int): Boolean
    }

    fun admitsAction(
        expected: Snapshot,
        candidateContext: CandidateActionContext?,
        tabContext: CandidateTabActionContext?,
    ): Boolean = owner.acceptsInput && expected.generation == owner.inputSession &&
        (candidateContext == null || candidateContext == owner.candidateActionContext) &&
        (tabContext == null || tabContext == owner.candidateTabActionContext)

    fun actionStillCurrent(
        expected: Snapshot,
        candidateContext: CandidateActionContext?,
        tabContext: CandidateTabActionContext?,
    ): Boolean = expected.generation == owner.inputSession && owner.snapshot?.revision == expected.revision &&
        (candidateContext == null || candidateContext == owner.candidateActionContext) &&
        (tabContext == null || tabContext == owner.candidateTabActionContext)

    fun candidatePageStillCurrent(expected: Snapshot, expectedVersion: Long): Boolean =
        owner.started && !owner.maintenance && expected.generation == owner.inputSession &&
            owner.candidateVersion == expectedVersion

    /** Called synchronously on the existing owner; raw input is queried only for this interaction. */
    fun moveCursor(position: Int, expectedSession: Long, expectedPreedit: String?): Boolean {
        val value = owner.snapshot ?: return false
        if (!owner.started || owner.maintenance || !value.ready || value.generation != owner.inputSession ||
            expectedSession != owner.inputSession) return false
        // CommitPreview is a prediction, not an editable representation of raw input.
        if (owner.preeditMode != "ComposingText" ||
            (expectedPreedit != null && expectedPreedit != owner.clientPreeditText)) {
            owner.restoreClientCursor(value.generation)
            return false
        }
        val raw = owner.rawInput(value)
        val target = raw?.let {
            preeditCursorToRawBytes(value.preedit, value.cursorUtf16, it.text, it.caretBytes, position)
        }
        if (raw == null || target == null) {
            owner.restoreClientCursor(value.generation)
            return false
        }
        if (target == raw.caretBytes) {
            owner.restoreClientCursor(value.generation)
            return true
        }
        return owner.moveRawCursor(target)
    }

    /** 手势起点只读一次；CommitPreview 是候选预览，不是可编辑编码。 */
    fun compositionEditAnchor(expectedSession: Long, expectedPreedit: String): CompositionEditAnchor? {
        val value = owner.snapshot ?: return null
        if (!owner.started || owner.maintenance || !value.ready || value.generation != owner.inputSession ||
            expectedSession != owner.inputSession || owner.preeditMode == "CommitPreview" ||
            value.preedit != expectedPreedit) return null
        val raw = owner.rawInput(value) ?: return null
        return CompositionEditAnchor(
            expectedSession, value.preedit, value.cursorUtf16, raw.text, raw.caretBytes
        )
    }

    /** 原始光标可以已经移动，但原始编码文本必须仍与锚点一致。 */
    private fun rawInputForAnchor(value: Snapshot, anchor: CompositionEditAnchor): RawInputState? {
        if (!owner.started || owner.maintenance || !value.ready || value.generation != owner.inputSession ||
            anchor.session != owner.inputSession) return null
        return owner.rawInput(value)?.takeIf { it.text == anchor.rawText }
    }

    fun moveCompositionCursor(anchor: CompositionEditAnchor, position: Int): Boolean {
        val value = owner.snapshot ?: return false
        val raw = rawInputForAnchor(value, anchor) ?: return false
        val target = preeditCursorToRawBytes(
            anchor.preedit, anchor.preeditCaretUtf16, anchor.rawText, anchor.rawCaretBytes, position
        ) ?: return false
        if (target == raw.caretBytes) return true
        return owner.moveRawCursor(target)
    }

    fun compositionRawCharacterCount(anchor: CompositionEditAnchor, first: Int, last: Int): Int? {
        val value = owner.snapshot ?: return null
        rawInputForAnchor(value, anchor) ?: return null
        val rawFirst = preeditCursorToRawBytes(
            anchor.preedit, anchor.preeditCaretUtf16, anchor.rawText, anchor.rawCaretBytes, first
        ) ?: return null
        val rawLast = preeditCursorToRawBytes(
            anchor.preedit, anchor.preeditCaretUtf16, anchor.rawText, anchor.rawCaretBytes, last
        ) ?: return null
        return rawCodePointCountBetween(anchor.rawText, rawFirst, rawLast)
    }
}
