import 'package:flutter/material.dart';

import '../controllers/game_controller.dart';

class GameCanvas extends StatelessWidget {
  const GameCanvas({super.key, required this.controller});

  final GameController controller;

  @override
  Widget build(BuildContext context) {
    return AspectRatio(
      aspectRatio: 9 / 16,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(24),
        child: Container(
          decoration: const BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: <Color>[
                Color(0xFFB3E5FC),
                Color(0xFFE1F5FE),
                Color(0xFFFFF8E1),
              ],
            ),
          ),
          child: Stack(
            children: <Widget>[
              Positioned(
                left: 0,
                right: 0,
                bottom: 0,
                height: controller.groundHeight,
                child: Container(color: const Color(0xFF81C784)),
              ),
              for (final coin in controller.coins)
                if (!coin.collected)
                  Positioned(
                    left: coin.x,
                    top: coin.y,
                    child: Container(
                      width: coin.size,
                      height: coin.size,
                      decoration: const BoxDecoration(
                        color: Color(0xFFFFD54F),
                        shape: BoxShape.circle,
                      ),
                    ),
                  ),
              for (final enemy in controller.enemies)
                Positioned(
                  left: enemy.x,
                  top: enemy.y,
                  child: Container(
                    width: enemy.width,
                    height: enemy.height,
                    decoration: BoxDecoration(
                      color: const Color(0xFFEF5350),
                      borderRadius: BorderRadius.circular(10),
                    ),
                  ),
                ),
              Positioned(
                left: controller.player.x,
                top: controller.player.y,
                child: Container(
                  width: controller.player.width,
                  height: controller.player.height,
                  decoration: BoxDecoration(
                    color: const Color(0xFF5C6BC0),
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
