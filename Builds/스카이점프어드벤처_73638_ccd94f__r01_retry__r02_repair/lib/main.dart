import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'crash_handler.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize('73638_ccd94f', 'kr.ac.kangwon.hai.skyjumpadventure');
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '스카이점프 어드벤처',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.lightBlue),
        scaffoldBackgroundColor: const Color(0xFFF3F8FF),
      ),
      home: const AppShell(),
    );
  }
}

enum AppScreen {
  title,
  stageSelect,
  game,
}

class AppShell extends StatefulWidget {
  const AppShell({super.key});

  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> {
  final SaveService _saveService = SaveService();
  int _bestScore = 0;
  int _unlockedStage = 1;
  int _selectedStage = 1;
  AppScreen _screen = AppScreen.title;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _loadSaveData();
  }

  Future<void> _loadSaveData() async {
    try {
      final bestScore = await _saveService.loadBestScore();
      final unlockedStage = await _saveService.loadUnlockedStage();
      if (!mounted) {
        return;
      }
      setState(() {
        _bestScore = bestScore;
        _unlockedStage = unlockedStage;
        _loading = false;
      });
    } catch (e, st) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: e,
          stack: st,
          library: 'save_load',
          context: ErrorDescription('저장 데이터를 불러오는 중 오류가 발생했습니다.'),
        ),
      );
      if (!mounted) {
        return;
      }
      setState(() {
        _bestScore = 0;
        _unlockedStage = 1;
        _loading = false;
      });
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('데이터를 불러오지 못했습니다')),
      );
    }
  }

  Future<void> _startStage(int stage) async {
    try {
      await SystemChrome.setPreferredOrientations(const [
        DeviceOrientation.landscapeLeft,
        DeviceOrientation.landscapeRight,
        DeviceOrientation.portraitUp,
      ]);
      if (!mounted) {
        return;
      }
      setState(() {
        _selectedStage = stage;
        _screen = AppScreen.game;
      });
    } catch (e, st) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: e,
          stack: st,
          library: 'orientation',
          context: ErrorDescription('게임 화면 방향을 설정하는 중 오류가 발생했습니다.'),
        ),
      );
      rethrow;
    }
  }

  Future<void> _handleGameFinished(GameResult result) async {
    try {
      final newBest = math.max(_bestScore, result.score);
      final newUnlocked = result.cleared
          ? math.max(_unlockedStage, math.min(result.stage + 1, StageRepository.stageCount))
          : _unlockedStage;
      await _saveService.saveBestScore(newBest);
      await _saveService.saveUnlockedStage(newUnlocked);
      if (!mounted) {
        return;
      }
      setState(() {
        _bestScore = newBest;
        _unlockedStage = newUnlocked;
      });
    } catch (e, st) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: e,
          stack: st,
          library: 'save_game_result',
          context: ErrorDescription('게임 결과를 저장하는 중 오류가 발생했습니다.'),
        ),
      );
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('저장에 실패했습니다')),
        );
      }
    }
  }

  Future<void> _backToTitle() async {
    try {
      await SystemChrome.setPreferredOrientations(const [
        DeviceOrientation.portraitUp,
        DeviceOrientation.landscapeLeft,
        DeviceOrientation.landscapeRight,
      ]);
    } catch (e, st) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: e,
          stack: st,
          library: 'orientation_reset',
          context: ErrorDescription('화면 방향을 복원하는 중 오류가 발생했습니다.'),
        ),
      );
    }
    if (!mounted) {
      return;
    }
    setState(() {
      _screen = AppScreen.title;
    });
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Scaffold(
        body: SingleChildScrollView(
          child: SizedBox(
            height: 600,
            child: Center(child: CircularProgressIndicator()),
          ),
        ),
      );
    }

    switch (_screen) {
      case AppScreen.title:
        return TitleScreen(
          bestScore: _bestScore,
          unlockedStage: _unlockedStage,
          onStart: () => _startStage(1),
          onContinue: () => _startStage(_unlockedStage),
          onStageSelect: () {
            setState(() {
              _screen = AppScreen.stageSelect;
            });
          },
        );
      case AppScreen.stageSelect:
        return StageSelectScreen(
          unlockedStage: _unlockedStage,
          onBack: _backToTitle,
          onSelectStage: _startStage,
        );
      case AppScreen.game:
        return GameScreen(
          stageNumber: _selectedStage,
          onExitToTitle: _backToTitle,
          onFinished: _handleGameFinished,
          onSelectStage: (stage) async {
            if (stage <= _unlockedStage) {
              await _startStage(stage);
            }
          },
        );
    }
  }
}

class TitleScreen extends StatelessWidget {
  const TitleScreen({
    super.key,
    required this.bestScore,
    required this.unlockedStage,
    required this.onStart,
    required this.onContinue,
    required this.onStageSelect,
  });

  final int bestScore;
  final int unlockedStage;
  final VoidCallback onStart;
  final VoidCallback onContinue;
  final VoidCallback onStageSelect;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      body: SingleChildScrollView(
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: MediaQuery.of(context).size.height),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const SizedBox(height: 48),
                Icon(
                  Icons.flight_takeoff_rounded,
                  size: 72,
                  color: theme.colorScheme.primary,
                ),
                const SizedBox(height: 16),
                Text(
                  '스카이점프 어드벤처',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.headlineMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 12),
                Text(
                  '하늘을 가로지르며 스테이지를 돌파하세요',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.bodyLarge,
                ),
                const SizedBox(height: 32),
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(20),
                    child: Column(
                      children: [
                        _InfoRow(label: '최고 점수', value: '$bestScore'),
                        const SizedBox(height: 12),
                        _InfoRow(label: '도달한 스테이지', value: '$unlockedStage'),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 24),
                ElevatedButton.icon(
                  key: UniqueKey(),
                  onPressed: onStart,
                  icon: const Icon(Icons.play_arrow_rounded),
                  label: const Padding(
                    padding: EdgeInsets.symmetric(vertical: 14),
                    child: Text('시작하기'),
                  ),
                ),
                const SizedBox(height: 12),
                FilledButton.tonalIcon(
                  key: UniqueKey(),
                  onPressed: onContinue,
                  icon: const Icon(Icons.restore_rounded),
                  label: const Padding(
                    padding: EdgeInsets.symmetric(vertical: 14),
                    child: Text('이어하기'),
                  ),
                ),
                const SizedBox(height: 12),
                OutlinedButton.icon(
                  key: UniqueKey(),
                  onPressed: onStageSelect,
                  icon: const Icon(Icons.map_rounded),
                  label: const Padding(
                    padding: EdgeInsets.symmetric(vertical: 14),
                    child: Text('스테이지 선택'),
                  ),
                ),
                const SizedBox(height: 48),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class StageSelectScreen extends StatelessWidget {
  const StageSelectScreen({
    super.key,
    required this.unlockedStage,
    required this.onBack,
    required this.onSelectStage,
  });

  final int unlockedStage;
  final Future<void> Function() onBack;
  final Future<void> Function(int stage) onSelectStage;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('스테이지 선택')),
      body: SingleChildScrollView(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            children: [
              for (int stage = 1; stage <= StageRepository.stageCount; stage++)
                Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: Card(
                    child: ListTile(
                      title: Text('스테이지 $stage'),
                      subtitle: Text(stage <= unlockedStage ? '도전 가능' : '잠금 상태'),
                      trailing: stage <= unlockedStage
                          ? ElevatedButton(
                              key: UniqueKey(),
                              onPressed: () async {
                                try {
                                  await onSelectStage(stage);
                                } catch (e, st) {
                                  FlutterError.reportError(
                                    FlutterErrorDetails(
                                      exception: e,
                                      stack: st,
                                      library: 'stage_select',
                                      context: ErrorDescription('스테이지를 여는 중 오류가 발생했습니다.'),
                                    ),
                                  );
                                  if (context.mounted) {
                                    ScaffoldMessenger.of(context).showSnackBar(
                                      const SnackBar(content: Text('스테이지를 불러오지 못했습니다')),
                                    );
                                  }
                                }
                              },
                              child: const Text('시작'),
                            )
                          : const Icon(Icons.lock_rounded),
                    ),
                  ),
                ),
              const SizedBox(height: 8),
              OutlinedButton.icon(
                key: UniqueKey(),
                onPressed: () async {
                  try {
                    await onBack();
                  } catch (e, st) {
                    FlutterError.reportError(
                      FlutterErrorDetails(
                        exception: e,
                        stack: st,
                        library: 'stage_select_back',
                        context: ErrorDescription('타이틀 화면으로 돌아가는 중 오류가 발생했습니다.'),
                      ),
                    );
                  }
                },
                icon: const Icon(Icons.arrow_back_rounded),
                label: const Text('타이틀로'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class GameScreen extends StatefulWidget {
  const GameScreen({
    super.key,
    required this.stageNumber,
    required this.onExitToTitle,
    required this.onFinished,
    required this.onSelectStage,
  });

  final int stageNumber;
  final Future<void> Function() onExitToTitle;
  final Future<void> Function(GameResult result) onFinished;
  final Future<void> Function(int stage) onSelectStage;

  @override
  State<GameScreen> createState() => _GameScreenState();
}

class _GameScreenState extends State<GameScreen> with SingleTickerProviderStateMixin {
  late final Ticker _ticker;
  late GameEngine _engine;
  Duration? _lastElapsed;
  bool _showResult = false;
  bool _savingResult = false;

  @override
  void initState() {
    super.initState();
    _createEngine();
    _ticker = createTicker(_onTick)..start();
  }

  void _createEngine() {
    try {
      _engine = GameEngine(stageNumber: widget.stageNumber);
    } catch (e, st) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: e,
          stack: st,
          library: 'game_engine_create',
          context: ErrorDescription('게임 엔진을 생성하는 중 오류가 발생했습니다.'),
        ),
      );
      _engine = GameEngine(stageNumber: 1);
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('데이터를 불러오지 못했습니다')),
          );
        }
      });
    }
  }

  void _safeRefresh() {
    if (!mounted) {
      return;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        setState(() {});
      }
    });
  }

  void _updateInput(void Function() updateEngine) {
    try {
      updateEngine();
      _safeRefresh();
    } catch (e, st) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: e,
          stack: st,
          library: 'game_input',
          context: ErrorDescription('게임 입력을 반영하는 중 오류가 발생했습니다.'),
        ),
      );
      rethrow;
    }
  }

  void _onTick(Duration elapsed) {
    final previous = _lastElapsed;
    _lastElapsed = elapsed;
    if (previous == null || _engine.isPaused || _showResult) {
      return;
    }
    final dt = (elapsed - previous).inMicroseconds / Duration.microsecondsPerSecond;
    try {
      _engine.update(dt.clamp(0.0, 0.033));
      if (_engine.isFinished && !_showResult) {
        _showResult = true;
        _persistResult();
      }
      if (mounted) {
        setState(() {});
      }
    } catch (e, st) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: e,
          stack: st,
          library: 'game_loop',
          context: ErrorDescription('게임 루프를 업데이트하는 중 오류가 발생했습니다.'),
        ),
      );
      rethrow;
    }
  }

  Future<void> _persistResult() async {
    if (_savingResult) {
      return;
    }
    _savingResult = true;
    try {
      await widget.onFinished(_engine.result);
    } catch (e, st) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: e,
          stack: st,
          library: 'game_result_persist',
          context: ErrorDescription('게임 결과를 저장하는 중 오류가 발생했습니다.'),
        ),
      );
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('저장에 실패했습니다')),
        );
      }
    } finally {
      _savingResult = false;
      if (mounted) {
        setState(() {});
      }
    }
  }

  @override
  void dispose() {
    _ticker.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SingleChildScrollView(
        child: SizedBox(
          height: MediaQuery.of(context).size.height,
          child: OrientationBuilder(
            builder: (context, orientation) {
              return Stack(
                children: [
                  Column(
                    children: [
                      GameHud(
                        score: _engine.score,
                        lives: _engine.player.lives,
                        stage: _engine.stage.number,
                        gems: _engine.collectedItems,
                      ),
                      Expanded(
                        child: Container(
                          color: _engine.stage.backgroundColor,
                          padding: const EdgeInsets.symmetric(horizontal: 8),
                          child: CustomPaint(
                            painter: GamePainter(engine: _engine),
                            child: const SizedBox.expand(),
                          ),
                        ),
                      ),
                      ControlPad(
                        onLeftChanged: (pressed) {
                          _updateInput(() {
                            _engine.leftPressed = pressed;
                          });
                        },
                        onRightChanged: (pressed) {
                          _updateInput(() {
                            _engine.rightPressed = pressed;
                          });
                        },
                        onJumpChanged: (pressed) {
                          _updateInput(() {
                            _engine.jumpPressed = pressed;
                            if (pressed) {
                              _engine.tryJump();
                            }
                          });
                        },
                      ),
                    ],
                  ),
                  Positioned(
                    top: 12,
                    right: 12,
                    child: FloatingActionButton.small(
                      key: UniqueKey(),
                      onPressed: () {
                        setState(() {
                          _engine.isPaused = !_engine.isPaused;
                        });
                      },
                      child: Icon(_engine.isPaused ? Icons.play_arrow_rounded : Icons.pause_rounded),
                    ),
                  ),
                  if (_engine.isPaused && !_showResult)
                    PauseOverlay(
                      onResume: () {
                        setState(() {
                          _engine.isPaused = false;
                        });
                      },
                      onRestart: () {
                        setState(() {
                          _showResult = false;
                          _lastElapsed = null;
                          _createEngine();
                        });
                      },
                      onExit: () async {
                        try {
                          await widget.onExitToTitle();
                        } catch (e, st) {
                          FlutterError.reportError(
                            FlutterErrorDetails(
                              exception: e,
                              stack: st,
                              library: 'pause_exit',
                              context: ErrorDescription('타이틀 화면으로 이동하는 중 오류가 발생했습니다.'),
                            ),
                          );
                        }
                      },
                    ),
                  if (_showResult)
                    ResultOverlay(
                      result: _engine.result,
                      onRetry: () {
                        setState(() {
                          _showResult = false;
                          _lastElapsed = null;
                          _createEngine();
                        });
                      },
                      onNextStage: _engine.result.cleared && widget.stageNumber < StageRepository.stageCount
                          ? () async {
                              try {
                                await widget.onSelectStage(widget.stageNumber + 1);
                              } catch (e, st) {
                                FlutterError.reportError(
                                  FlutterErrorDetails(
                                    exception: e,
                                    stack: st,
                                    library: 'next_stage',
                                    context: ErrorDescription('다음 스테이지로 이동하는 중 오류가 발생했습니다.'),
                                  ),
                                );
                              }
                            }
                          : null,
                      onExit: () async {
                        try {
                          await widget.onExitToTitle();
                        } catch (e, st) {
                          FlutterError.reportError(
                            FlutterErrorDetails(
                              exception: e,
                              stack: st,
                              library: 'result_exit',
                              context: ErrorDescription('결과 화면에서 타이틀로 이동하는 중 오류가 발생했습니다.'),
                            ),
                          );
                        }
                      },
                    ),
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}

class GameHud extends StatelessWidget {
  const GameHud({
    super.key,
    required this.score,
    required this.lives,
    required this.stage,
    required this.gems,
  });

  final int score;
  final int lives;
  final int stage;
  final int gems;

  @override
  Widget build(BuildContext context) {
    return Material(
      elevation: 2,
      color: Theme.of(context).colorScheme.surface,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text('점수 $score', style: Theme.of(context).textTheme.titleMedium),
            Text('생명 $lives', style: Theme.of(context).textTheme.titleMedium),
            Text('젬 $gems', style: Theme.of(context).textTheme.titleMedium),
            Text('스테이지 $stage', style: Theme.of(context).textTheme.titleMedium),
          ],
        ),
      ),
    );
  }
}

class ControlPad extends StatelessWidget {
  const ControlPad({
    super.key,
    required this.onLeftChanged,
    required this.onRightChanged,
    required this.onJumpChanged,
  });

  final ValueChanged<bool> onLeftChanged;
  final ValueChanged<bool> onRightChanged;
  final ValueChanged<bool> onJumpChanged;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.black.withValues(alpha: 0.08),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          children: [
            Expanded(
              child: Row(
                children: [
                  Expanded(
                    child: _HoldButton(
                      key: UniqueKey(),
                      icon: Icons.arrow_left_rounded,
                      label: '왼쪽',
                      onChanged: onLeftChanged,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: _HoldButton(
                      key: UniqueKey(),
                      icon: Icons.arrow_right_rounded,
                      label: '오른쪽',
                      onChanged: onRightChanged,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: _HoldButton(
                key: UniqueKey(),
                icon: Icons.arrow_upward_rounded,
                label: '점프',
                onChanged: onJumpChanged,
                color: Theme.of(context).colorScheme.primaryContainer,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _HoldButton extends StatefulWidget {
  const _HoldButton({
    super.key,
    required this.icon,
    required this.label,
    required this.onChanged,
    this.color,
  });

  final IconData icon;
  final String label;
  final ValueChanged<bool> onChanged;
  final Color? color;

  @override
  State<_HoldButton> createState() => _HoldButtonState();
}

class _HoldButtonState extends State<_HoldButton> {
  void _setPressed(bool value) {
    try {
      widget.onChanged(value);
    } catch (e, st) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: e,
          stack: st,
          library: 'control_pad',
          context: ErrorDescription('입력 상태를 변경하는 중 오류가 발생했습니다.'),
        ),
      );
      rethrow;
    }
  }

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTapDown: (_) => _setPressed(true),
      onTapUp: (_) => _setPressed(false),
      onTapCancel: () => _setPressed(false),
      onPanDown: (_) => _setPressed(true),
      onPanEnd: (_) => _setPressed(false),
      onPanCancel: () => _setPressed(false),
      child: Container(
        height: 84,
        decoration: BoxDecoration(
          color: widget.color ?? Theme.of(context).colorScheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(20),
        ),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(widget.icon, size: 32),
            const SizedBox(height: 4),
            Text(widget.label),
          ],
        ),
      ),
    );
  }
}

class PauseOverlay extends StatelessWidget {
  const PauseOverlay({
    super.key,
    required this.onResume,
    required this.onRestart,
    required this.onExit,
  });

  final VoidCallback onResume;
  final VoidCallback onRestart;
  final Future<void> Function() onExit;

  @override
  Widget build(BuildContext context) {
    return Container(
      color: Colors.black54,
      alignment: Alignment.center,
      child: SingleChildScrollView(
        child: Card(
          margin: const EdgeInsets.all(24),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text('일시정지', style: Theme.of(context).textTheme.headlineSmall),
                const SizedBox(height: 20),
                ElevatedButton(
                  key: UniqueKey(),
                  onPressed: onResume,
                  child: const Text('계속하기'),
                ),
                const SizedBox(height: 12),
                FilledButton.tonal(
                  key: UniqueKey(),
                  onPressed: onRestart,
                  child: const Text('다시 시작'),
                ),
                const SizedBox(height: 12),
                OutlinedButton(
                  key: UniqueKey(),
                  onPressed: () async {
                    try {
                      await onExit();
                    } catch (e, st) {
                      FlutterError.reportError(
                        FlutterErrorDetails(
                          exception: e,
                          stack: st,
                          library: 'pause_overlay_exit',
                          context: ErrorDescription('일시정지 화면에서 종료하는 중 오류가 발생했습니다.'),
                        ),
                      );
                    }
                  },
                  child: const Text('타이틀로'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class ResultOverlay extends StatelessWidget {
  const ResultOverlay({
    super.key,
    required this.result,
    required this.onRetry,
    required this.onNextStage,
    required this.onExit,
  });

  final GameResult result;
  final VoidCallback onRetry;
  final Future<void> Function()? onNextStage;
  final Future<void> Function() onExit;

  @override
  Widget build(BuildContext context) {
    return Container(
      color: Colors.black54,
      alignment: Alignment.center,
      child: SingleChildScrollView(
        child: Card(
          margin: const EdgeInsets.all(24),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  result.cleared ? '스테이지 클리어' : '게임 오버',
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                const SizedBox(height: 16),
                Text('점수 ${result.score}'),
                const SizedBox(height: 8),
                Text('획득한 스타젬 ${result.collectedItems}개'),
                const SizedBox(height: 20),
                ElevatedButton(
                  key: UniqueKey(),
                  onPressed: onRetry,
                  child: const Text('재도전'),
                ),
                if (onNextStage != null) ...[
                  const SizedBox(height: 12),
                  FilledButton.tonal(
                    key: UniqueKey(),
                    onPressed: () async {
                      try {
                        await onNextStage!.call();
                      } catch (e, st) {
                        FlutterError.reportError(
                          FlutterErrorDetails(
                            exception: e,
                            stack: st,
                            library: 'result_next_stage',
                            context: ErrorDescription('다음 스테이지로 이동하는 중 오류가 발생했습니다.'),
                          ),
                        );
                      }
                    },
                    child: const Text('다음 스테이지'),
                  ),
                ],
                const SizedBox(height: 12),
                OutlinedButton(
                  key: UniqueKey(),
                  onPressed: () async {
                    try {
                      await onExit();
                    } catch (e, st) {
                      FlutterError.reportError(
                        FlutterErrorDetails(
                          exception: e,
                          stack: st,
                          library: 'result_overlay_exit',
                          context: ErrorDescription('결과 화면에서 종료하는 중 오류가 발생했습니다.'),
                        ),
                      );
                    }
                  },
                  child: const Text('타이틀로'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class GamePainter extends CustomPainter {
  GamePainter({required this.engine});

  final GameEngine engine;

  @override
  void paint(Canvas canvas, Size size) {
    final scaleX = size.width / engine.stage.worldWidth;
    final scaleY = size.height / engine.stage.worldHeight;

    final backgroundPaint = Paint()..color = engine.stage.backgroundColor;
    canvas.drawRect(Offset.zero & size, backgroundPaint);

    final platformPaint = Paint()..color = const Color(0xFF5D7A3E);
    for (final platform in engine.stage.platforms) {
      canvas.drawRRect(
        RRect.fromRectAndRadius(
          Rect.fromLTWH(
            platform.x * scaleX,
            platform.y * scaleY,
            platform.width * scaleX,
            platform.height * scaleY,
          ),
          const Radius.circular(8),
        ),
        platformPaint,
      );
    }

    final goalPaint = Paint()..color = const Color(0xFFFFD54F);
    canvas.drawRect(
      Rect.fromLTWH(
        engine.stage.goal.x * scaleX,
        engine.stage.goal.y * scaleY,
        engine.stage.goal.width * scaleX,
        engine.stage.goal.height * scaleY,
      ),
      goalPaint,
    );

    final itemPaint = Paint()..color = const Color(0xFF7C4DFF);
    for (final item in engine.stage.items.where((item) => !item.collected)) {
      canvas.drawCircle(
        Offset((item.x + item.size / 2) * scaleX, (item.y + item.size / 2) * scaleY),
        item.size * scaleX / 2,
        itemPaint,
      );
    }

    final enemyPaint = Paint()..color = const Color(0xFFE53935);
    for (final enemy in engine.stage.enemies) {
      canvas.drawRRect(
        RRect.fromRectAndRadius(
          Rect.fromLTWH(
            enemy.x * scaleX,
            enemy.y * scaleY,
            enemy.width * scaleX,
            enemy.height * scaleY,
          ),
          const Radius.circular(10),
        ),
        enemyPaint,
      );
    }

    final playerPaint = Paint()..color = const Color(0xFF1E88E5);
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromLTWH(
          engine.player.x * scaleX,
          engine.player.y * scaleY,
          engine.player.width * scaleX,
          engine.player.height * scaleY,
        ),
        const Radius.circular(12),
      ),
      playerPaint,
    );
  }

  @override
  bool shouldRepaint(covariant GamePainter oldDelegate) => true;
}

class GameEngine {
  GameEngine({required int stageNumber}) : stage = StageRepository.loadStage(stageNumber) {
    player = Player(x: 24, y: stage.worldHeight - 120);
  }

  final StageData stage;
  late Player player;
  bool leftPressed = false;
  bool rightPressed = false;
  bool jumpPressed = false;
  bool isPaused = false;
  bool isFinished = false;
  bool isCleared = false;
  int score = 0;
  int collectedItems = 0;
  static const double gravity = 900;
  static const double moveSpeed = 220;
  static const double jumpVelocity = -420;

  GameResult get result => GameResult(
        stage: stage.number,
        score: score,
        collectedItems: collectedItems,
        cleared: isCleared,
      );

  void tryJump() {
    if (player.canJump && !isFinished) {
      player.vy = jumpVelocity;
      player.canJump = false;
    }
  }

  void update(double dt) {
    if (isPaused || isFinished) {
      return;
    }

    for (final enemy in stage.enemies) {
      enemy.update(dt);
    }

    player.vx = 0;
    if (leftPressed) {
      player.vx -= moveSpeed;
    }
    if (rightPressed) {
      player.vx += moveSpeed;
    }

    player.vy += gravity * dt;
    final previousY = player.y;
    player.x += player.vx * dt;
    player.y += player.vy * dt;

    if (player.x < 0) {
      player.x = 0;
    }
    if (player.x + player.width > stage.worldWidth) {
      player.x = stage.worldWidth - player.width;
    }

    player.canJump = false;
    for (final platform in stage.platforms) {
      if (_intersects(player.rect, platform.rect)) {
        final wasAbove = previousY + player.height <= platform.y + 4;
        if (player.vy >= 0 && wasAbove) {
          player.y = platform.y - player.height;
          player.vy = 0;
          player.canJump = true;
        }
      }
    }

    for (final item in stage.items.where((item) => !item.collected)) {
      if (_intersects(player.rect, item.rect)) {
        item.collected = true;
        collectedItems += 1;
        score += item.scoreValue;
      }
    }

    for (final enemy in stage.enemies) {
      if (_intersects(player.rect, enemy.rect)) {
        _loseLife();
        return;
      }
    }

    if (_intersects(player.rect, stage.goal.rect)) {
      isFinished = true;
      isCleared = true;
      score += 500;
    }

    if (player.y > stage.worldHeight + 80) {
      _loseLife();
    }
  }

  void _loseLife() {
    player.lives -= 1;
    if (player.lives <= 0) {
      isFinished = true;
      isCleared = false;
      return;
    }
    player.x = 24;
    player.y = stage.worldHeight - 120;
    player.vx = 0;
    player.vy = 0;
    player.canJump = false;
  }

  bool _intersects(Rect a, Rect b) => a.overlaps(b);
}

class Player {
  Player({required this.x, required this.y});

  double x;
  double y;
  double vx = 0;
  double vy = 0;
  double width = 28;
  double height = 36;
  bool canJump = false;
  int lives = 3;

  Rect get rect => Rect.fromLTWH(x, y, width, height);
}

class Enemy {
  Enemy({
    required this.x,
    required this.y,
    required this.minX,
    required this.maxX,
    this.speed = 70,
  });

  double x;
  double y;
  final double minX;
  final double maxX;
  double speed;
  double width = 28;
  double height = 28;
  int direction = 1;

  void update(double dt) {
    x += speed * direction * dt;
    if (x <= minX) {
      x = minX;
      direction = 1;
    } else if (x + width >= maxX) {
      x = maxX - width;
      direction = -1;
    }
  }

  Rect get rect => Rect.fromLTWH(x, y, width, height);
}

class ItemModel {
  ItemModel({required this.x, required this.y, this.scoreValue = 100});

  double x;
  double y;
  double size = 18;
  int scoreValue;
  bool collected = false;

  Rect get rect => Rect.fromLTWH(x, y, size, size);
}

class PlatformBlock {
  PlatformBlock({required this.x, required this.y, required this.width, required this.height});

  final double x;
  final double y;
  final double width;
  final double height;

  Rect get rect => Rect.fromLTWH(x, y, width, height);
}

class GoalZone {
  GoalZone({required this.x, required this.y, required this.width, required this.height});

  final double x;
  final double y;
  final double width;
  final double height;

  Rect get rect => Rect.fromLTWH(x, y, width, height);
}

class StageData {
  StageData({
    required this.number,
    required this.platforms,
    required this.enemies,
    required this.items,
    required this.goal,
    required this.backgroundColor,
    this.worldWidth = 800,
    this.worldHeight = 360,
  });

  final int number;
  final List<PlatformBlock> platforms;
  final List<Enemy> enemies;
  final List<ItemModel> items;
  final GoalZone goal;
  final Color backgroundColor;
  final double worldWidth;
  final double worldHeight;
}

class StageRepository {
  static const int stageCount = 3;

  static StageData loadStage(int stageNumber) {
    try {
      switch (stageNumber) {
        case 1:
          return StageData(
            number: 1,
            backgroundColor: const Color(0xFFB3E5FC),
            platforms: [
              PlatformBlock(x: 0, y: 320, width: 220, height: 40),
              PlatformBlock(x: 260, y: 270, width: 120, height: 24),
              PlatformBlock(x: 430, y: 230, width: 120, height: 24),
              PlatformBlock(x: 600, y: 290, width: 180, height: 24),
            ],
            enemies: [
              Enemy(x: 300, y: 242, minX: 260, maxX: 380),
              Enemy(x: 640, y: 262, minX: 600, maxX: 780),
            ],
            items: [
              ItemModel(x: 290, y: 235),
              ItemModel(x: 470, y: 195),
              ItemModel(x: 690, y: 255),
            ],
            goal: GoalZone(x: 740, y: 230, width: 24, height: 60),
          );
        case 2:
          return StageData(
            number: 2,
            backgroundColor: const Color(0xFFC8E6C9),
            platforms: [
              PlatformBlock(x: 0, y: 320, width: 160, height: 40),
              PlatformBlock(x: 190, y: 280, width: 100, height: 24),
              PlatformBlock(x: 330, y: 240, width: 100, height: 24),
              PlatformBlock(x: 470, y: 200, width: 100, height: 24),
              PlatformBlock(x: 620, y: 250, width: 160, height: 24),
            ],
            enemies: [
              Enemy(x: 205, y: 252, minX: 190, maxX: 290),
              Enemy(x: 500, y: 172, minX: 470, maxX: 570),
              Enemy(x: 660, y: 222, minX: 620, maxX: 780),
            ],
            items: [
              ItemModel(x: 220, y: 245),
              ItemModel(x: 360, y: 205),
              ItemModel(x: 500, y: 165),
              ItemModel(x: 700, y: 215),
            ],
            goal: GoalZone(x: 740, y: 190, width: 24, height: 60),
          );
        case 3:
          return StageData(
            number: 3,
            backgroundColor: const Color(0xFFD1C4E9),
            platforms: [
              PlatformBlock(x: 0, y: 320, width: 140, height: 40),
              PlatformBlock(x: 180, y: 290, width: 90, height: 24),
              PlatformBlock(x: 310, y: 250, width: 90, height: 24),
              PlatformBlock(x: 440, y: 210, width: 90, height: 24),
              PlatformBlock(x: 570, y: 170, width: 90, height: 24),
              PlatformBlock(x: 690, y: 230, width: 90, height: 24),
            ],
            enemies: [
              Enemy(x: 190, y: 262, minX: 180, maxX: 270, speed: 90),
              Enemy(x: 450, y: 182, minX: 440, maxX: 530, speed: 100),
              Enemy(x: 700, y: 202, minX: 690, maxX: 780, speed: 110),
            ],
            items: [
              ItemModel(x: 205, y: 255),
              ItemModel(x: 335, y: 215),
              ItemModel(x: 465, y: 175),
              ItemModel(x: 595, y: 135),
              ItemModel(x: 720, y: 195),
            ],
            goal: GoalZone(x: 748, y: 170, width: 24, height: 60),
          );
        default:
          return loadStage(1);
      }
    } catch (e, st) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: e,
          stack: st,
          library: 'stage_repository',
          context: ErrorDescription('스테이지 데이터를 구성하는 중 오류가 발생했습니다.'),
        ),
      );
      return loadStage(1);
    }
  }
}

class SaveService {
  static const String _bestScoreKey = 'best_score';
  static const String _unlockedStageKey = 'unlocked_stage';

  Future<SharedPreferences> _prefs() async {
    try {
      return SharedPreferences.getInstance();
    } catch (e, st) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: e,
          stack: st,
          library: 'shared_preferences',
          context: ErrorDescription('로컬 저장소를 여는 중 오류가 발생했습니다.'),
        ),
      );
      rethrow;
    }
  }

  Future<int> loadBestScore() async {
    try {
      final prefs = await _prefs();
      return prefs.getInt(_bestScoreKey) ?? 0;
    } catch (e, st) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: e,
          stack: st,
          library: 'load_best_score',
          context: ErrorDescription('최고 점수를 불러오는 중 오류가 발생했습니다.'),
        ),
      );
      return 0;
    }
  }

  Future<int> loadUnlockedStage() async {
    try {
      final prefs = await _prefs();
      final value = prefs.getInt(_unlockedStageKey) ?? 1;
      return value < 1 ? 1 : value;
    } catch (e, st) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: e,
          stack: st,
          library: 'load_unlocked_stage',
          context: ErrorDescription('진행 스테이지를 불러오는 중 오류가 발생했습니다.'),
        ),
      );
      return 1;
    }
  }

  Future<void> saveBestScore(int score) async {
    try {
      final prefs = await _prefs();
      await prefs.setInt(_bestScoreKey, score);
    } catch (e, st) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: e,
          stack: st,
          library: 'save_best_score',
          context: ErrorDescription('최고 점수를 저장하는 중 오류가 발생했습니다.'),
        ),
      );
      rethrow;
    }
  }

  Future<void> saveUnlockedStage(int stage) async {
    try {
      final prefs = await _prefs();
      await prefs.setInt(_unlockedStageKey, stage);
    } catch (e, st) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: e,
          stack: st,
          library: 'save_unlocked_stage',
          context: ErrorDescription('진행 스테이지를 저장하는 중 오류가 발생했습니다.'),
        ),
      );
      rethrow;
    }
  }
}

class GameResult {
  const GameResult({
    required this.stage,
    required this.score,
    required this.collectedItems,
    required this.cleared,
  });

  final int stage;
  final int score;
  final int collectedItems;
  final bool cleared;
}

class _InfoRow extends StatelessWidget {
  const _InfoRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(label, style: Theme.of(context).textTheme.titleMedium),
        Text(value, style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
      ],
    );
  }
}
