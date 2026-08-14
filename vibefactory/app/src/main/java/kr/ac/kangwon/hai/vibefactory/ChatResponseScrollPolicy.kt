package kr.ac.kangwon.hai.vibefactory

internal object ChatResponseScrollPolicy {
    fun shouldClearPendingScroll(
        pending: Boolean,
        scrollNow: Boolean,
        pinBottomForTransientUpdate: Boolean
    ): Boolean {
        return pending && !scrollNow && !pinBottomForTransientUpdate
    }
}
