import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

class CrashHandler {
  CrashHandler._();

  static final ValueNotifier<String?> latestErrorMessage =
      ValueNotifier<String?>(null);

  static String _taskId = 'unknown';
  static String _packageName = 'unknown';
  static bool _initialized = false;

  static Future<void> initialize(String taskId, String packageName) async {
    if (_initialized) {
      return;
    }
    _initialized = true;
    _taskId = taskId;
    _packageName = packageName;

    FlutterError.onError = (FlutterErrorDetails details) {
      FlutterError.presentError(details);
      recordError(details.exception, details.stack ?? StackTrace.current);
    };

    PlatformDispatcher.instance.onError = (Object error, StackTrace stackTrace) {
      recordError(error, stackTrace, fatal: true);
      return true;
    };
  }

  static Future<void> recordError(
    Object error,
    StackTrace stackTrace, {
    bool fatal = false,
  }) async {
    latestErrorMessage.value = '오류가 발생했지만 앱은 계속 사용할 수 있습니다.';
    debugPrint(
      'CrashHandler($_taskId,$_packageName,fatal=$fatal): $error\n$stackTrace',
    );
  }
}

class CrashBannerListener extends StatelessWidget {
  const CrashBannerListener({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<String?>(
      valueListenable: CrashHandler.latestErrorMessage,
      builder: (context, message, _) {
        return Stack(
          children: [
            child,
            if (message != null && message.isNotEmpty)
              Positioned(
                left: 12,
                right: 12,
                bottom: 12,
                child: Material(
                  color: Colors.transparent,
                  child: Card(
                    color: Theme.of(context).colorScheme.errorContainer,
                    child: ListTile(
                      leading: const Icon(Icons.error_outline),
                      title: Text(message),
                      trailing: IconButton(
                        onPressed: () {
                          CrashHandler.latestErrorMessage.value = null;
                        },
                        icon: const Icon(Icons.close),
                      ),
                    ),
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}
