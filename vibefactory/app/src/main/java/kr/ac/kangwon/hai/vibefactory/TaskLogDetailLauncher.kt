package kr.ac.kangwon.hai.vibefactory

import android.content.Context
import android.content.Intent
import com.google.gson.GsonBuilder

internal object TaskLogDetailLauncher {
    private val gson = GsonBuilder().create()

    fun open(
        context: Context,
        taskId: String?,
        summary: TaskSummary?,
        currentStatus: String,
        displayedAppName: String?,
        messages: List<ChatMessage>,
        rawLogContents: List<String>,
        formatTimestamp: (String?) -> String?
    ) {
        val payload = TaskLogDetailFormatter.buildPayload(
            taskId = taskId.orEmpty(),
            summary = summary,
            currentStatus = currentStatus,
            displayedAppName = displayedAppName,
            messages = messages,
            rawLogContents = rawLogContents,
            formatTimestamp = formatTimestamp
        )
        context.startActivity(
            Intent(context, TaskLogDetailActivity::class.java)
                .putExtra(TaskLogDetailActivity.EXTRA_PAYLOAD, gson.toJson(payload))
        )
    }
}
