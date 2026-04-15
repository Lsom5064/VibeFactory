import 'package:flutter/material.dart';

import '../core/constants/app_colors.dart';
import '../core/utils/date_utils.dart';
import '../models/daily_menu.dart';

class WeeklyMenuTable extends StatelessWidget {
  const WeeklyMenuTable({
    super.key,
    required this.days,
  });

  final List<DailyMenu> days;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Table(
        defaultVerticalAlignment: TableCellVerticalAlignment.middle,
        border: TableBorder.all(color: AppColors.border, width: 1),
        columnWidths: const {
          0: FixedColumnWidth(88),
          1: FixedColumnWidth(64),
          2: FixedColumnWidth(180),
          3: FixedColumnWidth(180),
          4: FixedColumnWidth(180),
        },
        children: [
          _headerRow(),
          ...days.map(_dataRow),
        ],
      ),
    );
  }

  TableRow _headerRow() {
    return const TableRow(
      decoration: BoxDecoration(color: Color(0xFFF7F7F7)),
      children: [
        _HeaderCell('날짜'),
        _HeaderCell('요일'),
        _HeaderCell('아침'),
        _HeaderCell('점심'),
        _HeaderCell('저녁'),
      ],
    );
  }

  TableRow _dataRow(DailyMenu day) {
    final isSunday = day.date.weekday == DateTime.sunday;
    return TableRow(
      children: [
        _BodyCell(
          AppDateUtils.formatMonthDay(day.date),
          textColor: isSunday ? AppColors.sundayAccent : AppColors.dateAccent,
          fontWeight: FontWeight.w700,
        ),
        _BodyCell(
          day.weekdayLabel,
          backgroundColor: AppColors.weekdayBackground,
          textColor: isSunday ? AppColors.sundayAccent : null,
          fontWeight: FontWeight.w700,
        ),
        _BodyCell(_joinMenus(day.breakfastMenus)),
        _BodyCell(_joinMenus(day.lunchMenus)),
        _BodyCell(_joinMenus(day.dinnerMenus)),
      ],
    );
  }

  String _joinMenus(List<String> menus) {
    if (menus.isEmpty) {
      return '식단 없음';
    }
    return menus.join('\n');
  }
}

class _HeaderCell extends StatelessWidget {
  const _HeaderCell(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(10),
      child: Text(
        text,
        textAlign: TextAlign.center,
        style: const TextStyle(fontWeight: FontWeight.w800),
      ),
    );
  }
}

class _BodyCell extends StatelessWidget {
  const _BodyCell(
    this.text, {
    this.backgroundColor,
    this.textColor,
    this.fontWeight,
  });

  final String text;
  final Color? backgroundColor;
  final Color? textColor;
  final FontWeight? fontWeight;

  @override
  Widget build(BuildContext context) {
    return Container(
      color: backgroundColor,
      padding: const EdgeInsets.all(10),
      child: Text(
        text,
        style: TextStyle(color: textColor, fontWeight: fontWeight, height: 1.4),
      ),
    );
  }
}
