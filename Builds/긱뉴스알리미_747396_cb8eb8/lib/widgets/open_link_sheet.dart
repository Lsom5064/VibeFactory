import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

class OpenLinkSheet extends StatefulWidget {
  final String title;
  final String url;

  const OpenLinkSheet({
    super.key,
    required this.title,
    required this.url,
  });

  @override
  State<OpenLinkSheet> createState() => _OpenLinkSheetState();
}

class _OpenLinkSheetState extends State<OpenLinkSheet> {
  bool _opening = false;
  String? _error;

  Future<void> _open() async {
    setState(() {
      _opening = true;
      _error = null;
    });
    try {
      final uri = Uri.parse(widget.url);
      final success = await launchUrl(uri, mode: LaunchMode.externalApplication);
      if (!success && mounted) {
        setState(() {
          _error = '브라우저를 열 수 없습니다.';
          _opening = false;
        });
        return;
      }
      if (mounted) {
        Navigator.of(context).pop();
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _error = '브라우저를 열 수 없습니다.';
          _opening = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Center(
              child: Container(
                width: 40,
                height: 4,
                margin: const EdgeInsets.only(bottom: 16),
                decoration: BoxDecoration(
                  color: Colors.grey.shade400,
                  borderRadius: BorderRadius.circular(999),
                ),
              ),
            ),
            Text(widget.title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            const Text('시스템 브라우저에서 링크를 엽니다.'),
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(_error!, style: const TextStyle(color: Colors.red)),
            ],
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: FilledButton(
                    onPressed: _opening ? null : _open,
                    child: Text(_opening ? '여는 중...' : '열기'),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: TextButton(
                    onPressed: _opening ? null : () => Navigator.of(context).pop(),
                    child: const Text('취소'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
