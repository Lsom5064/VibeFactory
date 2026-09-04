import tempfile
import unittest
from pathlib import Path

from flutter_apk_server.ui_catalog import (
    UI_CATALOG_RELATIVE_PATH,
    catalog_layout_metadata,
    ensure_valid_ui_catalog,
    fallback_display_name,
    validate_ui_catalog,
)


MAIN_LAYOUT = """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    <TextView
        android:id="@+id/title"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/title" />
    <Button
        android:id="@+id/saveButton"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="저장" />
</LinearLayout>
"""

ITEM_LAYOUT = """<?xml version="1.0" encoding="utf-8"?>
<TextView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/itemTitle"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="항목" />
"""


class UiCatalogTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.project = Path(self.temp_dir.name) / "project"
        resource_root = self.project / "app/src/main/res"
        (resource_root / "layout").mkdir(parents=True)
        (resource_root / "layout-land").mkdir(parents=True)
        (resource_root / "values").mkdir(parents=True)
        (resource_root / "layout/activity_main.xml").write_text(MAIN_LAYOUT, encoding="utf-8")
        (resource_root / "layout-land/activity_main.xml").write_text(MAIN_LAYOUT, encoding="utf-8")
        (resource_root / "layout/item_result.xml").write_text(ITEM_LAYOUT, encoding="utf-8")
        (resource_root / "values/strings.xml").write_text(
            '<resources><string name="title">검사 결과</string></resources>',
            encoding="utf-8",
        )
        source = self.project / "app/src/main/kotlin/example/MainActivity.kt"
        source.parent.mkdir(parents=True)
        source.write_text(
            """package example
class MainActivity {
    fun render() { val layout = R.layout.activity_main }
}
""",
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def write_catalog(self, body: str, guide_version: str = "rev_0001") -> None:
        path = self.project / UI_CATALOG_RELATIVE_PATH
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            f'<ui-catalog schemaVersion="1" guideVersion="{guide_version}">\n'
            f"{body}\n"
            "</ui-catalog>\n",
            encoding="utf-8",
        )

    def test_missing_catalog_is_repaired_with_every_layout_variant(self) -> None:
        self.assertFalse(validate_ui_catalog(self.project)["valid"])

        report = ensure_valid_ui_catalog(self.project, guide_version="rev_0007")

        self.assertTrue(report["valid"], report["issues"])
        self.assertTrue(report["repaired"])
        catalog = report["catalog"]
        self.assertEqual("rev_0007", catalog["guide_version"])
        keys = {
            (item["configuration"], item["layout_name"])
            for item in catalog["layouts"]
        }
        self.assertEqual(
            {
                ("layout", "activity_main"),
                ("layout-land", "activity_main"),
                ("layout", "item_result"),
            },
            keys,
        )
        screens = [item for item in catalog["layouts"] if item["layout_kind"] == "screen"]
        self.assertEqual(2, len(screens))
        self.assertTrue(all(item["activity_class"] == "example.MainActivity" for item in screens))
        self.assertTrue(all(item["elements"] for item in screens))
        item = next(item for item in catalog["layouts"] if item["layout_kind"] == "item")
        self.assertEqual(["itemTitle"], [element["view_id"] for element in item["elements"]])

    def test_validator_rejects_duplicate_stale_and_unknown_view_ids(self) -> None:
        self.write_catalog(
            """
    <layout layoutName="activity_main" configuration="layout" displayName="메인 화면" kind="screen" activityClass="example.MainActivity">
        <element viewId="missing" title="없는 버튼" description="없는 버튼입니다." order="1" />
    </layout>
    <layout layoutName="activity_main" configuration="layout" displayName="중복 화면" kind="screen" activityClass="example.MainActivity">
        <element viewId="saveButton" title="저장" description="내용을 저장합니다." order="1" />
    </layout>
    <layout layoutName="removed" configuration="layout" displayName="삭제된 화면" kind="component" />
"""
        )

        report = validate_ui_catalog(self.project)

        self.assertFalse(report["valid"])
        joined = "\n".join(report["issues"])
        self.assertIn("duplicate layout entry", joined)
        self.assertIn("catalog references missing layout", joined)
        self.assertIn("guide viewId does not exist", joined)
        self.assertIn("layout is not registered", joined)

    def test_validator_and_repair_enforce_unambiguous_layout_roles(self) -> None:
        self.write_catalog(
            """
    <layout layoutName="activity_main" configuration="layout" displayName="메인 화면" kind="screen" activityClass="example.MainActivity">
        <element viewId="saveButton" title="저장" description="내용을 저장합니다." order="1" />
    </layout>
    <layout layoutName="activity_main" configuration="layout-land" displayName="가로 화면" kind="screen" activityClass="example.MainActivity">
        <element viewId="saveButton" title="저장" description="내용을 저장합니다." order="1" />
    </layout>
    <layout layoutName="item_result" configuration="layout" displayName="결과 항목" kind="component">
        <element viewId="itemTitle" title="결과" description="결과 내용을 확인합니다." order="1" />
    </layout>
"""
        )

        invalid = validate_ui_catalog(self.project)
        self.assertFalse(invalid["valid"])
        self.assertTrue(any("must be item" in issue for issue in invalid["issues"]))

        repaired = ensure_valid_ui_catalog(self.project, guide_version="rev_0002")
        self.assertTrue(repaired["valid"], repaired["issues"])
        item = next(entry for entry in repaired["catalog"]["layouts"] if entry["layout_name"] == "item_result")
        self.assertEqual("item", item["layout_kind"])

    def test_repair_preserves_safe_copy_and_removes_invalid_metadata(self) -> None:
        self.write_catalog(
            """
    <layout layoutName="activity_main" configuration="layout" displayName="오늘 화면" kind="screen" activityClass="example.MainActivity">
        <element viewId="saveButton" title="저장하기" description="작성한 내용을 보관합니다." order="4" />
        <element viewId="missing" title="누락" description="없는 요소입니다." order="8" />
    </layout>
    <layout layoutName="activity_main" configuration="layout-land" displayName="/Users/private.xml" kind="wrong" />
"""
        )

        report = ensure_valid_ui_catalog(self.project, guide_version="rev_0002")

        self.assertTrue(report["valid"], report["issues"])
        main = next(
            item for item in report["catalog"]["layouts"]
            if item["layout_name"] == "activity_main" and item["configuration"] == "layout"
        )
        self.assertEqual("오늘 화면", main["display_name"])
        self.assertEqual(["saveButton"], [item["view_id"] for item in main["elements"]])
        self.assertEqual([1], [item["order"] for item in main["elements"]])
        land = next(
            item for item in report["catalog"]["layouts"]
            if item["configuration"] == "layout-land"
        )
        self.assertEqual("screen", land["layout_kind"])
        self.assertNotIn(".xml", land["display_name"])

    def test_unsafe_user_facing_text_blocks_validation(self) -> None:
        self.write_catalog(
            """
    <layout layoutName="activity_main" configuration="layout" displayName="메인 화면" kind="screen" activityClass="example.MainActivity">
        <element viewId="saveButton" title="R.id.saveButton" description="API key를 입력합니다." order="1" />
    </layout>
    <layout layoutName="activity_main" configuration="layout-land" displayName="가로 화면" kind="screen" activityClass="example.MainActivity">
        <element viewId="saveButton" title="저장" description="내용을 저장합니다." order="1" />
    </layout>
    <layout layoutName="item_result" configuration="layout" displayName="결과 항목" kind="item" />
"""
        )

        report = validate_ui_catalog(self.project)

        self.assertFalse(report["valid"])
        self.assertTrue(any("invalid guide title" in issue for issue in report["issues"]))
        self.assertTrue(any("invalid guide description" in issue for issue in report["issues"]))
        metadata = catalog_layout_metadata(self.project)
        self.assertEqual("메인 화면", metadata[("activity_main", "layout")]["display_name"])
        self.assertEqual("item", metadata[("item_result", "layout")]["layout_kind"])

    def test_catalog_metadata_and_legacy_fallback_are_user_friendly(self) -> None:
        self.assertEqual({}, catalog_layout_metadata(self.project))
        self.assertEqual("메인 화면", fallback_display_name("activity_main"))
        self.assertEqual("결과 항목", fallback_display_name("item_result"))

        report = ensure_valid_ui_catalog(self.project, guide_version="rev_0001")
        self.assertTrue(report["valid"])
        metadata = catalog_layout_metadata(self.project)
        self.assertTrue(metadata[("activity_main", "layout")]["guide_available"])
        self.assertEqual("item", metadata[("item_result", "layout")]["layout_kind"])


if __name__ == "__main__":
    unittest.main()
