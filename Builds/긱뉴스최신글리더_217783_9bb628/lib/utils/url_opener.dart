import 'dart:io';

class UrlOpener {
  static Future<bool> openExternal(String rawUrl) async {
    final String normalized = rawUrl.trim();
    final Uri? uri = Uri.tryParse(normalized);

    if (uri == null || !(uri.scheme == 'http' || uri.scheme == 'https') || uri.host.isEmpty) {
      return false;
    }

    try {
      if (Platform.isAndroid) {
        await Process.start('am', <String>[
          'start',
          '-a',
          'android.intent.action.VIEW',
          '-d',
          uri.toString(),
        ]);
        return true;
      }

      if (Platform.isIOS || Platform.isMacOS) {
        await Process.start('open', <String>[uri.toString()]);
        return true;
      }

      if (Platform.isLinux) {
        await Process.start('xdg-open', <String>[uri.toString()]);
        return true;
      }

      if (Platform.isWindows) {
        await Process.start('cmd', <String>['/c', 'start', '', uri.toString()]);
        return true;
      }
    } catch (_) {
      return false;
    }

    return false;
  }
}
