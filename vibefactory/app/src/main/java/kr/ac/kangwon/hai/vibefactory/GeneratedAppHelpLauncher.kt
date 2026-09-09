package kr.ac.kangwon.hai.vibefactory

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast

internal object GeneratedAppHelpLauncher {
    const val RESTORE_ACTIVITY = "kr.ac.kangwon.hai.generated.UiGuideRestoreActivity"

    fun restore(context: Context, packageName: String?) {
        val message = if (packageName.isNullOrBlank() ||
            context.packageManager.getLaunchIntentForPackage(packageName) == null) {
            R.string.generated_help_install_first
        } else {
            val intent = Intent().setComponent(ComponentName(packageName, RESTORE_ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
            R.string.generated_help_update_required
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
