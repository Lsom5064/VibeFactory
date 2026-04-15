import 'package:flutter/material.dart';

class StatusCard extends StatelessWidget {
  final String title;
  final String message;
  final IconData icon;

  const StatusCard({
    super.key,
    required this.title,
    required this.message,
    this.icon = Icons.info_outline,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      child: ListTile(
        leading: Icon(icon),
        title: Text(title),
        subtitle: Text(message),
      ),
    );
  }
}
