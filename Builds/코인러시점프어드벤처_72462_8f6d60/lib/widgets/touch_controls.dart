import 'package:flutter/material.dart';

class TouchControls extends StatelessWidget {
  const TouchControls({
    super.key,
    required this.onLeftDown,
    required this.onLeftUp,
    required this.onRightDown,
    required this.onRightUp,
    required this.onJump,
  });

  final VoidCallback onLeftDown;
  final VoidCallback onLeftUp;
  final VoidCallback onRightDown;
  final VoidCallback onRightUp;
  final VoidCallback onJump;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: <Widget>[
          Row(
            children: <Widget>[
              GestureDetector(
                onTapDown: (_) => onLeftDown(),
                onTapUp: (_) => onLeftUp(),
                onTapCancel: onLeftUp,
                child: Semantics(
                  label: '왼쪽 이동 버튼',
                  button: true,
                  child: Container(
                    width: 84,
                    height: 84,
                    decoration: BoxDecoration(
                      color: Colors.blue.shade100,
                      borderRadius: BorderRadius.circular(24),
                    ),
                    child: const Icon(Icons.arrow_left_rounded, size: 42),
                  ),
                ),
              ),
              const SizedBox(width: 12),
              GestureDetector(
                onTapDown: (_) => onRightDown(),
                onTapUp: (_) => onRightUp(),
                onTapCancel: onRightUp,
                child: Semantics(
                  label: '오른쪽 이동 버튼',
                  button: true,
                  child: Container(
                    width: 84,
                    height: 84,
                    decoration: BoxDecoration(
                      color: Colors.green.shade100,
                      borderRadius: BorderRadius.circular(24),
                    ),
                    child: const Icon(Icons.arrow_right_rounded, size: 42),
                  ),
                ),
              ),
            ],
          ),
          ElevatedButton.icon(
            key: UniqueKey(),
            onPressed: onJump,
            icon: const Icon(Icons.arrow_upward_rounded),
            label: const Text('점프'),
            style: ElevatedButton.styleFrom(
              minimumSize: const Size(120, 84),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(24),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
