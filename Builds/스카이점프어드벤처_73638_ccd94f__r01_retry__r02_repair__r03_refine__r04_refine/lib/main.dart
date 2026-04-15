import 'package:flutter/material.dart';
import 'package:skyjump_adventure/crash_handler.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize('73638_ccd94f__r01_retry__r02_repair__r03_refine__r04', 'skyjump_adventure');
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    final ColorScheme colorScheme = ColorScheme.fromSeed(
      seedColor: const Color(0xFFFF8FC7),
      brightness: Brightness.light,
    );

    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: '스카이점프 어드벤처',
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: colorScheme,
        scaffoldBackgroundColor: const Color(0xFFFFF8FC),
        snackBarTheme: const SnackBarThemeData(
          behavior: SnackBarBehavior.floating,
        ),
      ),
      home: const _GameHomeScreen(),
    );
  }
}

class _GameHomeScreen extends StatefulWidget {
  const _GameHomeScreen();

  @override
  State<_GameHomeScreen> createState() => _GameHomeScreenState();
}

class _GameHomeScreenState extends State<_GameHomeScreen> {
  int _selectedTabIndex = 0;

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);

    return Scaffold(
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _buildHeader(theme),
              const SizedBox(height: 20),
              _buildHeroCard(theme),
              const SizedBox(height: 20),
              FilledButton(
                key: UniqueKey(),
                onPressed: () {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('게임 시작은 곧 제공됩니다!')),
                  );
                },
                style: FilledButton.styleFrom(
                  backgroundColor: const Color(0xFF63D66E),
                  foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(vertical: 18),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(24),
                  ),
                  elevation: 2,
                ),
                child: const Text(
                  'START GAME',
                  style: TextStyle(
                    fontSize: 20,
                    fontWeight: FontWeight.w900,
                    letterSpacing: 0.8,
                  ),
                ),
              ),
              const SizedBox(height: 20),
              LayoutBuilder(
                builder: (context, constraints) {
                  final bool stacked = constraints.maxWidth < 420;
                  final children = [
                    Expanded(
                      child: _InfoCard(
                        title: 'CURRENT LEVEL',
                        value: 'WORLD 1-1',
                        color: const Color(0xFFFFE7A8),
                        icon: Icons.flag_rounded,
                      ),
                    ),
                    const SizedBox(width: 12, height: 12),
                    Expanded(
                      child: _InfoCard(
                        title: 'HIGH SCORE',
                        value: '99,420',
                        color: const Color(0xFFDDF4FF),
                        icon: Icons.stars_rounded,
                      ),
                    ),
                  ];

                  if (stacked) {
                    return Column(
                      children: [
                        _InfoCard(
                          title: 'CURRENT LEVEL',
                          value: 'WORLD 1-1',
                          color: const Color(0xFFFFE7A8),
                          icon: Icons.flag_rounded,
                        ),
                        const SizedBox(height: 12),
                        _InfoCard(
                          title: 'HIGH SCORE',
                          value: '99,420',
                          color: const Color(0xFFDDF4FF),
                          icon: Icons.stars_rounded,
                        ),
                      ],
                    );
                  }

                  return Row(children: children);
                },
              ),
              const SizedBox(height: 24),
              _buildBottomTabs(theme),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildHeader(ThemeData theme) {
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: const Color(0xFFFFB3D9),
        borderRadius: BorderRadius.circular(28),
        boxShadow: const [
          BoxShadow(
            color: Color(0x1A000000),
            blurRadius: 18,
            offset: Offset(0, 8),
          ),
        ],
      ),
      child: Row(
        children: [
          const CircleAvatar(
            radius: 28,
            backgroundColor: Colors.white,
            child: Icon(Icons.person_rounded, color: Color(0xFFFF7EB6), size: 30),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'PLAYER ONE',
                  style: theme.textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.w900,
                    color: const Color(0xFF7A2450),
                  ),
                ),
                const SizedBox(height: 6),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.9),
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: const Text(
                    'SUPER STAR',
                    style: TextStyle(
                      fontWeight: FontWeight.w800,
                      color: Color(0xFFB24A7A),
                    ),
                  ),
                ),
              ],
            ),
          ),
          IconButton.filledTonal(
            key: UniqueKey(),
            onPressed: () {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('설정 기능은 준비 중입니다.')),
              );
            },
            style: IconButton.styleFrom(
              backgroundColor: Colors.white.withValues(alpha: 0.85),
              foregroundColor: const Color(0xFF9C3D6A),
            ),
            icon: const Icon(Icons.settings_rounded),
          ),
        ],
      ),
    );
  }

  Widget _buildHeroCard(ThemeData theme) {
    return Container(
      height: 300,
      decoration: BoxDecoration(
        color: const Color(0xFF9FE7FF),
        borderRadius: BorderRadius.circular(32),
        boxShadow: const [
          BoxShadow(
            color: Color(0x22000000),
            blurRadius: 24,
            offset: Offset(0, 12),
          ),
        ],
      ),
      child: Stack(
        children: [
          Positioned(
            top: 24,
            left: 20,
            child: _cloud(width: 72),
          ),
          Positioned(
            top: 42,
            right: 28,
            child: _coin(),
          ),
          Positioned(
            top: 88,
            right: 18,
            child: _cloud(width: 56),
          ),
          Positioned(
            left: 24,
            top: 34,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'MARIO WORLD',
                  style: theme.textTheme.headlineMedium?.copyWith(
                    fontWeight: FontWeight.w900,
                    color: const Color(0xFF0D4D73),
                    height: 1,
                  ),
                ),
                const SizedBox(height: 8),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.9),
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: const Text(
                    'NEW ADVENTURE',
                    style: TextStyle(
                      fontWeight: FontWeight.w800,
                      color: Color(0xFF1A6A96),
                    ),
                  ),
                ),
              ],
            ),
          ),
          Positioned(
            left: 24,
            right: 24,
            bottom: 24,
            child: Row(
              children: [
                Expanded(
                  child: Container(
                    height: 120,
                    decoration: BoxDecoration(
                      color: const Color(0xFFFFD86B),
                      borderRadius: BorderRadius.circular(28),
                      boxShadow: const [
                        BoxShadow(
                          color: Color(0x22000000),
                          blurRadius: 16,
                          offset: Offset(0, 8),
                        ),
                      ],
                    ),
                    child: const Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        CircleAvatar(
                          radius: 26,
                          backgroundColor: Colors.white,
                          child: Icon(Icons.videogame_asset_rounded, color: Color(0xFFE08A00), size: 28),
                        ),
                        SizedBox(height: 10),
                        Text(
                          'JUMP HERO',
                          style: TextStyle(
                            fontWeight: FontWeight.w900,
                            color: Color(0xFF8A5200),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(width: 14),
                Container(
                  width: 92,
                  height: 120,
                  decoration: BoxDecoration(
                    color: const Color(0xFFFFF4CC),
                    borderRadius: BorderRadius.circular(28),
                  ),
                  child: const Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(Icons.rocket_launch_rounded, color: Color(0xFF5A9CFF), size: 34),
                      SizedBox(height: 8),
                      Text(
                        'GO!',
                        style: TextStyle(
                          fontWeight: FontWeight.w900,
                          color: Color(0xFF3A6FC0),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildBottomTabs(ThemeData theme) {
    final tabs = <({IconData icon, String label})>[
      (icon: Icons.home_rounded, label: 'HOME'),
      (icon: Icons.map_rounded, label: 'LEVELS'),
      (icon: Icons.shield_moon_rounded, label: 'HEROES'),
      (icon: Icons.storefront_rounded, label: 'SHOP'),
    ];

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: const Color(0xFFCFF59A),
        borderRadius: BorderRadius.circular(30),
      ),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 10),
        decoration: BoxDecoration(
          color: Colors.white.withValues(alpha: 0.95),
          borderRadius: BorderRadius.circular(24),
          boxShadow: const [
            BoxShadow(
              color: Color(0x14000000),
              blurRadius: 12,
              offset: Offset(0, 4),
            ),
          ],
        ),
        child: Row(
          children: List.generate(tabs.length, (index) {
            final tab = tabs[index];
            final bool selected = _selectedTabIndex == index;
            return Expanded(
              child: InkWell(
                key: UniqueKey(),
                borderRadius: BorderRadius.circular(18),
                onTap: () {
                  setState(() {
                    _selectedTabIndex = index;
                  });
                },
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 180),
                  padding: const EdgeInsets.symmetric(vertical: 10),
                  decoration: BoxDecoration(
                    color: selected ? const Color(0xFFE8F8D0) : Colors.transparent,
                    borderRadius: BorderRadius.circular(18),
                  ),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        tab.icon,
                        color: selected ? const Color(0xFF5A9A1F) : const Color(0xFF7A7A7A),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        tab.label,
                        style: TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.w800,
                          color: selected ? const Color(0xFF5A9A1F) : const Color(0xFF7A7A7A),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            );
          }),
        ),
      ),
    );
  }

  Widget _cloud({required double width}) {
    return SizedBox(
      width: width,
      height: width * 0.5,
      child: Stack(
        children: [
          Positioned(
            left: 0,
            bottom: 0,
            child: CircleAvatar(radius: width * 0.16, backgroundColor: Colors.white),
          ),
          Positioned(
            left: width * 0.18,
            top: 0,
            child: CircleAvatar(radius: width * 0.18, backgroundColor: Colors.white),
          ),
          Positioned(
            right: width * 0.12,
            bottom: 0,
            child: CircleAvatar(radius: width * 0.15, backgroundColor: Colors.white),
          ),
          Positioned(
            left: width * 0.12,
            right: width * 0.08,
            bottom: 0,
            child: Container(
              height: width * 0.22,
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(999),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _coin() {
    return Container(
      width: 42,
      height: 42,
      decoration: BoxDecoration(
        color: const Color(0xFFFFD54F),
        shape: BoxShape.circle,
        border: Border.all(color: const Color(0xFFFFF1A8), width: 3),
      ),
      child: const Icon(Icons.attach_money_rounded, color: Color(0xFF9A6A00)),
    );
  }
}

class _InfoCard extends StatelessWidget {
  const _InfoCard({
    required this.title,
    required this.value,
    required this.color,
    required this.icon,
  });

  final String title;
  final String value;
  final Color color;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(24),
        boxShadow: const [
          BoxShadow(
            color: Color(0x14000000),
            blurRadius: 14,
            offset: Offset(0, 6),
          ),
        ],
      ),
      child: Row(
        children: [
          CircleAvatar(
            radius: 22,
            backgroundColor: Colors.white.withValues(alpha: 0.9),
            child: Icon(icon, color: const Color(0xFF5C5C5C)),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w800,
                    color: Color(0xFF6A6A6A),
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  value,
                  style: const TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.w900,
                    color: Color(0xFF2F2F2F),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _HoldButton extends StatefulWidget {
  const _HoldButton({
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
  final Set<int> _activePointers = <int>{};
  bool _isPressed = false;

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

  void _emitPressedIfNeeded(bool value) {
    if (_isPressed == value) {
      return;
    }
    setState(() {
      _isPressed = value;
    });
    _setPressed(value);
  }

  void _handlePointerDown(PointerDownEvent event) {
    _activePointers.add(event.pointer);
    if (_activePointers.length == 1) {
      _emitPressedIfNeeded(true);
    }
  }

  void _handlePointerEnd(PointerEvent event) {
    _activePointers.remove(event.pointer);
    if (_activePointers.isEmpty) {
      _emitPressedIfNeeded(false);
    }
  }

  @override
  void dispose() {
    if (_isPressed) {
      _setPressed(false);
    }
    _activePointers.clear();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final Color baseColor = widget.color ?? Theme.of(context).colorScheme.surfaceContainerHighest;
    final Color pressedColor = Color.lerp(baseColor, Theme.of(context).colorScheme.primary, 0.12) ?? baseColor;

    return Listener(
      behavior: HitTestBehavior.opaque,
      onPointerDown: _handlePointerDown,
      onPointerUp: _handlePointerEnd,
      onPointerCancel: _handlePointerEnd,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 80),
        height: 84,
        decoration: BoxDecoration(
          color: _isPressed ? pressedColor : baseColor,
          borderRadius: BorderRadius.circular(20),
          border: Border.all(
            color: _isPressed
                ? Theme.of(context).colorScheme.primary.withValues(alpha: 0.7)
                : Colors.transparent,
            width: 2,
          ),
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
