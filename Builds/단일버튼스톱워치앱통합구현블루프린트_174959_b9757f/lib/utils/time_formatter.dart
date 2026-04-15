class TimeFormatter {
  const TimeFormatter._();

  static String formatHms(Duration duration) {
    final Duration safeDuration = duration.isNegative ? Duration.zero : duration;
    final int totalSeconds = safeDuration.inSeconds;
    final int hours = totalSeconds ~/ 3600;
    final int minutes = (totalSeconds % 3600) ~/ 60;
    final int seconds = totalSeconds % 60;

    final String hh = hours.toString().padLeft(2, '0');
    final String mm = minutes.toString().padLeft(2, '0');
    final String ss = seconds.toString().padLeft(2, '0');

    return '$hh:$mm:$ss';
  }
}
