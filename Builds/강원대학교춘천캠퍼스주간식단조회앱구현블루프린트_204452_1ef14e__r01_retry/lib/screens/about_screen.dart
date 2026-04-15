import 'package:flutter/material.dart';

class AboutScreen extends StatelessWidget {
  const AboutScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('앱 정보')),
      body: const SingleChildScrollView(
        padding: EdgeInsets.all(16),
        child: Text('강원대학교 춘천캠퍼스 주간 식단 조회 앱입니다.'),
      ),
    );
  }
}
