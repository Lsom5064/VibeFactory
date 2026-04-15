String formatStopwatchDuration(Duration duration) {
  try {
    final Duration safeDuration = duration.isNegative ? Duration.zero : duration;
    final int totalCentiseconds = safeDuration.inMilliseconds ~/ 10;
    final int minutes = totalCentiseconds ~/ 6000;
    final int seconds = (totalCentiseconds % 6000) ~/ 100;
    final int centiseconds = totalCentiseconds % 100;

    final String minuteText = minutes.toString().padLeft(2, '0');
    final String secondText = seconds.toString().padLeft(2, '0');
    final String centisecondText = centiseconds.toString().padLeft(2, '0');

    return '$minuteText:$secondText.$centisecondText';
  } catch (_) {
    return '00:00.00';
  }
}
