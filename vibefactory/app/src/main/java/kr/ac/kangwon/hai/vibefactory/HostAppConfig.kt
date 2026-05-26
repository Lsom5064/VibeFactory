package kr.ac.kangwon.hai.vibefactory

import com.google.gson.Gson
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object HostAppConfig {
    const val BASE_URL = "http://192.168.0.82:8000"
    const val PREFS_NAME = "vibefactory_prefs"
    const val PREF_DEVICE_ID = "device_id"
    const val PREF_PHONE_NUMBER = "phone_number"
    const val PREF_LAST_TASK_ID = "last_task_id"
    const val PREF_TASK_TIMELINES = "task_timelines"
    const val PREF_TASK_STATUS_BUBBLES = "task_status_bubbles"
    const val PREF_PENDING_RUNTIME_ERRORS = "pending_runtime_errors"
    const val PREF_HIDDEN_TASK_IDS = "hidden_task_ids"
    const val PREF_NOTIFIED_BUILD_SUCCESS_TASK_IDS = "notified_build_success_task_ids"
    const val PREF_MONITORED_TASK_IDS = "foreground_monitored_task_ids"
    const val PREF_MONITORED_TASK_APP_NAMES = "foreground_monitored_task_app_names"
    const val PREF_NOTIFIED_TERMINAL_TASK_IDS = "foreground_notified_terminal_task_ids"
    const val PREF_TASK_ARTIFACT_STATES = "task_artifact_states"
    const val PREF_TOKEN_LIMIT = "token_limit"
    const val PREF_DARK_MODE_ENABLED = "dark_mode_enabled"
}

fun createVibeApiService(
    gson: Gson? = null,
    connectTimeoutSeconds: Long = 15,
    readTimeoutSeconds: Long = 120,
    writeTimeoutSeconds: Long = 120,
    callTimeoutSeconds: Long? = 150
): VibeApiService {
    val clientBuilder = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)

    if (callTimeoutSeconds != null) {
        clientBuilder.callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
    }

    val converterFactory = gson?.let { GsonConverterFactory.create(it) }
        ?: GsonConverterFactory.create()

    return Retrofit.Builder()
        .baseUrl(HostAppConfig.BASE_URL)
        .client(clientBuilder.build())
        .addConverterFactory(converterFactory)
        .build()
        .create(VibeApiService::class.java)
}
