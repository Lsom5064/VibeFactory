package kr.ac.kangwon.hai.vibefactory

import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

fun AppCompatActivity.applyRootSystemBarPadding(applyTop: Boolean = true, applyBottom: Boolean = true) {
    val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) ?: return
    root.applySystemBarPadding(applyTop = applyTop, applyBottom = applyBottom)
}

fun View.applySystemBarPadding(applyTop: Boolean = true, applyBottom: Boolean = true) {
    val baseLeft = paddingLeft
    val baseTop = paddingTop
    val baseRight = paddingRight
    val baseBottom = paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.updatePadding(
            left = baseLeft + systemBars.left,
            top = baseTop + if (applyTop) systemBars.top else 0,
            right = baseRight + systemBars.right,
            bottom = baseBottom + if (applyBottom) systemBars.bottom else 0
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
