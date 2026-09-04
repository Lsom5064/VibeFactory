package kr.ac.kangwon.hai.vibefactory.ui_editor

internal class UiAnnotationDragScrollGate {
    private var enteredSafeZone = false

    fun shouldAutoScroll(isInsideSafeZone: Boolean): Boolean {
        if (isInsideSafeZone) {
            enteredSafeZone = true
            return false
        }
        return enteredSafeZone
    }

    fun reset() {
        enteredSafeZone = false
    }
}
