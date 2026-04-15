import 'package:flutter/foundation.dart';

import '../models/menu_models.dart';
import '../services/cache_repository.dart';
import '../services/menu_scraper_service.dart';

class MenuController extends ChangeNotifier {
  MenuController({
    MenuScraperService? scraperService,
    CacheRepository? cacheRepository,
  })  : _scraperService = scraperService ?? MenuScraperService(),
        _cacheRepository = cacheRepository ?? CacheRepository();

  final MenuScraperService _scraperService;
  final CacheRepository _cacheRepository;

  WeeklyMenu? weeklyMenu;
  bool isLoading = false;
  String? errorMessage;
  String? transientMessage;

  Future<void> fetchCurrentWeek() async {
    transientMessage = null;
    errorMessage = null;
    isLoading = true;
    notifyListeners();

    try {
      final menu = await _scraperService.fetchCurrentWeek();
      weeklyMenu = menu;
      await _cacheRepository.saveWeeklyMenu(menu);
    } catch (error) {
      errorMessage = error.toString();
      weeklyMenu = await _cacheRepository.loadWeeklyMenu();
      if (weeklyMenu != null) {
        transientMessage = '네트워크 오류로 캐시 데이터를 표시합니다.';
      }
    } finally {
      isLoading = false;
      notifyListeners();
    }
  }
}
