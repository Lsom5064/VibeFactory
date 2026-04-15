import 'package:flutter/material.dart';

class AppDateUtils {
  const AppDateUtils._();

  static String toIsoDate(DateTime dateTime) {
    final local = dateTime.toLocal();
    final year = local.year.toString().padLeft(4, '0');
    final month = local.month.toString().padLeft(2, '0');
    final day = local.day.toString().padLeft(2, '0');
    return '$year-$month-$day';
  }

  static DateTime? tryParseIsoDate(String value) {
    try {
      return DateTime.parse(value);
    } catch (_) {
      return null;
    }
  }

  static String formatKoreanDate(String isoDate) {
    final parsed = tryParseIsoDate(isoDate);
    if (parsed == null) {
      return isoDate;
    }
    return '${parsed.year}년 ${parsed.month}월 ${parsed.day}일';
  }

  static String formatDateTime(BuildContext context, DateTime dateTime) {
    final local = dateTime.toLocal();
    final timeOfDay = TimeOfDay.fromDateTime(local);
    return '${local.year}년 ${local.month}월 ${local.day}일 ${timeOfDay.format(context)}';
  }
}
