import 'package:flutter/material.dart';

import '../models/article_item.dart';

class ArticleCard extends StatelessWidget {
  final ArticleItem item;
  final VoidCallback onTap;

  const ArticleCard({
    super.key,
    required this.item,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final List<Widget> metaWidgets = <Widget>[];

    if (item.publishedTime != null) {
      metaWidgets.add(
        Text(
          item.publishedTime!,
          style: Theme.of(context).textTheme.bodySmall,
        ),
      );
    }

    if (item.sourceOrAuthor != null) {
      if (metaWidgets.isNotEmpty) {
        metaWidgets.add(const SizedBox(width: 8));
      }
      metaWidgets.add(
        Flexible(
          child: Text(
            item.sourceOrAuthor!,
            overflow: TextOverflow.ellipsis,
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ),
      );
    }

    return Card(
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text(
                item.title,
                style: Theme.of(context).textTheme.titleMedium,
              ),
              if (metaWidgets.isNotEmpty) ...<Widget>[
                const SizedBox(height: 12),
                Row(children: metaWidgets),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
