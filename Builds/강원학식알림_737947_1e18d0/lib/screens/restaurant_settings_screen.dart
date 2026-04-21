import 'package:flutter/material.dart';

import '../services/crash_handler.dart';
import '../services/menu_repository.dart';
import '../services/settings_repository.dart';

class RestaurantSettingsScreen extends StatefulWidget {
  const RestaurantSettingsScreen({super.key});

  @override
  State<RestaurantSettingsScreen> createState() => _RestaurantSettingsScreenState();
}

class _RestaurantSettingsScreenState extends State<RestaurantSettingsScreen> {
  final SettingsRepository _settingsRepository = SettingsRepository();
  final MenuRepository _menuRepository = MenuRepository();

  List<String> _restaurants = <String>[];
  String _savedSelection = '';
  String? _tempSelection;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final settings = await _settingsRepository.loadUserSettings();
      final restaurants = await _menuRepository.getAvailableRestaurants();
      if (!mounted) {
        return;
      }
      setState(() {
        _savedSelection = settings.selectedRestaurantName;
        _tempSelection =
            settings.selectedRestaurantName.isEmpty ? null : settings.selectedRestaurantName;
        _restaurants = restaurants;
        _loading = false;
      });
    } catch (error, stackTrace) {
      await CrashHandler.logError(error, stackTrace);
      if (!mounted) {
        return;
      }
      setState(() {
        _loading = false;
      });
    }
  }

  Future<void> _save() async {
    if (_tempSelection == null || _tempSelection!.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('식당을 선택한 뒤 저장해 주세요.')),
      );
      return;
    }
    try {
      await _settingsRepository.saveSelectedRestaurant(_tempSelection!);
      if (!mounted) {
        return;
      }
      Navigator.of(context).pop(true);
    } catch (error, stackTrace) {
      await CrashHandler.logError(error, stackTrace);
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('식당 저장에 실패했습니다. 다시 시도해 주세요.')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('식당 선택 설정')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : SingleChildScrollView(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: <Widget>[
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: <Widget>[
                          Text('현재 선택 식당', style: Theme.of(context).textTheme.titleMedium),
                          const SizedBox(height: 8),
                          Text(_savedSelection.isEmpty ? '아직 선택하지 않았습니다.' : _savedSelection),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 12),
                  Text('오늘 날짜 기준으로 확인된 식당 목록입니다.', style: Theme.of(context).textTheme.bodyMedium),
                  const SizedBox(height: 12),
                  if (_restaurants.isEmpty)
                    const Card(
                      child: Padding(
                        padding: EdgeInsets.all(16),
                        child: Text('오늘 조회된 식당 정보가 없습니다. 홈 화면에서 먼저 새로고침해 주세요.'),
                      ),
                    )
                  else
                    RadioGroup<String>(
                      groupValue: _tempSelection,
                      onChanged: (String? value) {
                        setState(() {
                          _tempSelection = value;
                        });
                      },
                      child: Column(
                        children: _restaurants
                            .map(
                              (String restaurant) => Card(
                                child: RadioListTile<String>(
                                  value: restaurant,
                                  title: Text(restaurant),
                                ),
                              ),
                            )
                            .toList(),
                      ),
                    ),
                  const SizedBox(height: 12),
                  FilledButton(
                    onPressed: _tempSelection == null ? null : _save,
                    child: const Text('저장'),
                  ),
                ],
              ),
            ),
    );
  }
}
