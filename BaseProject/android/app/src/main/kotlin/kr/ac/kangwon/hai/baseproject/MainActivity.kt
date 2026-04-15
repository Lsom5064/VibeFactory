package kr.ac.kangwon.hai.baseproject

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

import android.content.Intent

class MainActivity: FlutterActivity() {

    private val CHANNEL = "kr.ac.kangwon.hai/crash"
    private val HOST_PACKAGE = "kr.ac.kangwon.hai.vibefactory"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->

                if (call.method == "reportCrash") {
                    // 데이터 추출 (null 방지 처리)
                    val taskId = call.argument<String>("task_id") ?: "unknown"
                    val pkg = call.argument<String>("package_name") ?: "unknown"
                    val stack = call.argument<String>("stack_trace") ?: "unknown"

                    // 메인 앱으로 브로드캐스트 전송
                    val intent = Intent("kr.ac.kangwon.hai.action.CRASH_REPORT").apply {
                        `package` = HOST_PACKAGE
                        putExtra("task_id", taskId)
                        putExtra("package_name", pkg)
                        putExtra("stack_trace", stack)
                    }
                    sendBroadcast(intent)

                    result.success(null)
                } else {
                    result.notImplemented()
                }
            }
    }
}
