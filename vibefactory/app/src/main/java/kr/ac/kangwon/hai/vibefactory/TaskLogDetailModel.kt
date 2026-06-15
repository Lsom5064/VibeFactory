package kr.ac.kangwon.hai.vibefactory

data class TaskLogDetailPayload(
    val title: String,
    val appName: String,
    val taskId: String,
    val status: String,
    val statusTone: String,
    val lastUpdated: String,
    val progressItems: List<TaskLogDetailItem>,
    val agentItems: List<TaskLogDetailItem>,
    val apkAction: TaskLogApkAction?
)

data class TaskLogDetailItem(
    val time: String,
    val label: String,
    val body: String,
    val detail: String? = null
)

data class TaskLogApkAction(
    val taskId: String,
    val title: String,
    val meta: String,
    val apkUrl: String?,
    val artifactPath: String?,
    val downloadedPath: String?
)
