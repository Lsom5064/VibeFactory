import 'package:flutter/material.dart';

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
