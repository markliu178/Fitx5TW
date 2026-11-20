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

    /**
     * 移动组合文本的编码光标，面板预编辑（PreeditMode=No）下同样可用。
     *
     * 与 [moveCursor] 不同，这里不要求客户端预编辑存在，但要求当前快照的编码文本
     * 与手势开始时一致，避免提交、切会话或换方案后误移动光标。
     */
    fun moveCompositionCursor(
        position: Int,
        expectedSession: Long,
        expectedPreedit: String
    ): Boolean {
        val value = owner.snapshot ?: return false
        if (!owner.started || owner.maintenance || !value.ready || value.generation != owner.inputSession ||
            expectedSession != owner.inputSession || value.preedit != expectedPreedit) return false
        val raw = owner.rawInput(value) ?: return false
        val target = preeditCursorToRawBytes(
            value.preedit, value.cursorUtf16, raw.text, raw.caretBytes, position
        ) ?: return false
        if (target == raw.caretBytes) return true
        return owner.moveRawCursor(target)
    }

    /** 显示区间 [first]..[last] 对应的原始编码字符数，分隔符不计入。 */
    fun compositionRawCharacterCount(
        first: Int,
        last: Int,
        expectedSession: Long,
        expectedPreedit: String
    ): Int? {
        val value = owner.snapshot ?: return null
        if (!owner.started || owner.maintenance || !value.ready || value.generation != owner.inputSession ||
            expectedSession != owner.inputSession || value.preedit != expectedPreedit) return null
        val raw = owner.rawInput(value) ?: return null
        val rawFirst = preeditCursorToRawBytes(
            value.preedit, value.cursorUtf16, raw.text, raw.caretBytes, first
        ) ?: return null
        val rawLast = preeditCursorToRawBytes(
            value.preedit, value.cursorUtf16, raw.text, raw.caretBytes, last
        ) ?: return null
        return rawCodePointCountBetween(raw.text, rawFirst, rawLast)
    }
}
