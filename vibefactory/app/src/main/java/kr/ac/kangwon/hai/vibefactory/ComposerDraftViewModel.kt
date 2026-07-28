package kr.ac.kangwon.hai.vibefactory

import androidx.lifecycle.ViewModel

internal class ComposerDraftViewModel : ViewModel() {
    val selectedAttachments: MutableList<SelectedAttachment> = mutableListOf()
}
