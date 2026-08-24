package kr.ac.kangwon.hai.vibefactory

import android.content.Intent
import android.provider.MediaStore

object AttachmentPickerIntentFactory {
    fun multipleImages(): Intent {
        return Intent(Intent.ACTION_PICK).apply {
            setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }
}
