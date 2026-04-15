import 'package:flutter/material.dart';

class MenuSectionCard extends StatelessWidget {
  final String title;
  final List<String> items;

  const MenuSectionCard({
    super.key,
    required this.title,
    required this.items,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            if (items.isEmpty)
              Text(
                '등록된 메뉴가 없습니다.',
                style: Theme.of(context).textTheme.bodyMedium,
              )
            else
              ...items.map(
                (item) => Padding(
                  padding: const EdgeInsets.only(bottom: 6),
                  child: Text('• $item'),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
