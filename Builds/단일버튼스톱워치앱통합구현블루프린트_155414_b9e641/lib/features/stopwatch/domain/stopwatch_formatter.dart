String formatStopwatchElapsed(Duration duration) {
  final safeDuration = duration.isNegative ? Duration.zero : duration;
  final totalMinutes = safeDuration.inMinutes;
  final seconds = safeDuration.inSeconds % 60;
  final centiseconds = (safeDuration.inMilliseconds % 1000) ~/ 10;

  final minuteText = totalMinutes.toString().padLeft(2, '0');
  final secondText = seconds.toString().padLeft(2, '0');
  final centisecondText = centiseconds.toString().padLeft(2, '0');

  return '$minuteText:$secondText.$centisecondText';
}
