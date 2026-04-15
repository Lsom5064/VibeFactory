import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import '../models/menu_models.dart';
import '../services/menu_scraper_service.dart';

class WeeklyMenuController extends ChangeNotifier {
  WeeklyMenuController({MenuScraperService? scraperService})
      : _scraperService = scraperService ?? MenuScraperService();

  final MenuScraperService _scraperService;

  bool isLoading = false;
  String? errorMessage;
  Map<String, dynamic>? weeklyMenu;

  Future<void> fetchCurrentWeek() async {
    try {
      isLoading = true;
      errorMessage = null;
      notifyListeners();

      final weekly = await _scraperService.fetchCurrentWeek();
      if (weekly.entries.isEmpty) {
        throw Exception('식단 정보를 파싱하지 못했습니다.');
      }

      weeklyMenu = _groupByDate(weekly);
      if (weeklyMenu == null || weeklyMenu!.isEmpty) {
        throw Exception('식단 정보를 파싱하지 못했습니다.');
      }
    } on http.ClientException {
      errorMessage = '네트워크 오류가 발생했습니다. 잠시 후 다시 시도해주세요.';
    } on Exception catch (e) {
      final message = e.toString();
      if (message.contains('SocketException') || message.contains('TimeoutException')) {
        errorMessage = '네트워크 오류가 발생했습니다. 잠시 후 다시 시도해주세요.';
      } else {
        errorMessage = '식단 정보를 파싱하지 못했습니다. 잠시 후 다시 시도해주세요.';
      }
    } catch (_) {
      errorMessage = '식단 정보를 파싱하지 못했습니다. 잠시 후 다시 시도해주세요.';
    } finally {
      isLoading = false;
      notifyListeners();
    }
  }

  Map<String, dynamic> _groupByDate(WeeklyMenu weekly) {
    final grouped = <String, List<String>>{};

    for (final DailyMenu entry in weekly.entries) {
      final line = '${entry.restaurant} · ${entry.section}: ${entry.items.join(', ')}';
      grouped.putIfAbsent(entry.date, () => <String>[]).add(line);
    }

    return grouped;
  }
}
