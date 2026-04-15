import 'package:flutter/material.dart';

class ControlPad extends StatelessWidget {
  const ControlPad({
    super.key,
    required this.onMoveLeftStart,
    required this.onMoveLeftEnd,
    required this.onMoveRightStart,
    required this.onMoveRightEnd,
    required this.onJump,
  });

  final VoidCallback onMoveLeftStart;
  final VoidCallback onMoveLeftEnd;
  final VoidCallback onMoveRightStart;
  final VoidCallback onMoveRightEnd;
  final VoidCallback onJump;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: Row(
            children: [
              _HoldButton(
                icon: Icons.arrow_left_rounded,
                label: '왼쪽',
                onPressStart: onMoveLeftStart,
                onPressEnd: onMoveLeftEnd,
              ),
              const SizedBox(width: 12),
              _HoldButton(
                icon: Icons.arrow_right_rounded,
                label: '오른쪽',
                onPressStart: onMoveRightStart,
                onPressEnd: onMoveRightEnd,
              ),
            ],
          ),
        ),
        const SizedBox(width: 16),
        SizedBox(
          width: 110,
          height: 110,
          child: ElevatedButton(
            key: UniqueKey(),
            style: ElevatedButton.styleFrom(
              shape: const CircleBorder(),
              backgroundColor: const Color(0xFFFFCA28),
              foregroundColor: Colors.black87,
            ),
            onPressed: onJump,
            child: const Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(Icons.arrow_upward_rounded, size: 34),
                SizedBox(height: 4),
                Text('점프'),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _HoldButton extends StatelessWidget {
  const _HoldButton({
    required this.icon,
    required this.label,
    required this.onPressStart,
    required this.onPressEnd,
  });

  final IconData icon;
  final String label;
  final VoidCallback onPressStart;
  final VoidCallback onPressEnd;

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: GestureDetector(
        onTapDown: (_) => onPressStart(),
        onTapUp: (_) => onPressEnd(),
        onTapCancel: onPressEnd,
        child: Container(
          key: UniqueKey(),
          height: 84,
          decoration: BoxDecoration(
            color: const Color(0xFF1565C0),
            borderRadius: BorderRadius.circular(24),
          ),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(icon, color: Colors.white, size: 34),
              const SizedBox(height: 4),
              Text(
                label,
                style: const TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
