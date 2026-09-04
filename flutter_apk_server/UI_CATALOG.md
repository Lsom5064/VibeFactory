# UI Catalog Contract

Native Android projects keep user-facing layout names and guide steps in:

`app/src/main/res/xml/vf_ui_catalog.xml`

The file is generated or updated by Codex and validated by the server before lint or APK build.

```xml
<ui-catalog schemaVersion="1" guideVersion="rev_0002">
    <layout
        layoutName="activity_main"
        configuration="layout"
        displayName="메인 화면"
        kind="screen"
        activityClass="kr.ac.kangwon.hai.generated.MainActivity">
        <element
            viewId="saveButton"
            title="저장"
            description="작성한 내용을 저장합니다."
            order="1" />
    </layout>
</ui-catalog>
```

## Layout Rules

- Register every XML file below every `res/layout*` directory exactly once.
- `layoutName` is the file stem and `configuration` is the exact resource directory name.
- Configuration variants share `layoutName` but retain separate `configuration` entries.
- `displayName` is concise, user-facing Korean text. It must not contain file names, paths, View IDs, variable names, credentials, or API details.
- `kind` is one of `screen`, `component`, `dialog`, or `item`.
- A `screen` requires the fully qualified host `activityClass` and at least one guide element.
- Guide elements use IDs that exist in that exact layout file. Explain meaningful controls and information areas only.
- `guideVersion` follows the generated revision label so an updated APK can show its updated guide once even when sideloading uses a stable Android version code.

## Runtime Rules

`GeneratedApplication` initializes `UiGuideController`. Activity screens are detected automatically. For UI that is not an Activity root, call the controller after the target has been laid out:

```kotlin
UiGuideController.show(dialog, "dialog_help")
UiGuideController.show(activity, "item_result", itemView)
UiGuideController.show(activity, "component_summary", summaryView)
```

Use `UiGuideController.replay(activity)` from an app menu or settings action. The runtime fallback replay control remains available when an app has no menu.

The guide is an overlay. Never add guide copy to the product layout itself.

## Server Validation

Before a build, the server checks schema and revision versions, complete layout coverage, duplicate entries, kinds, Activity classes, guide ordering, text safety, and View ID existence. It performs one deterministic repair pass that preserves valid authored metadata and fills recoverable omissions. If validation still fails, the build does not start and the task receives a user-facing failure result.

The UI editor layout APIs also return optional `display_name`, `layout_kind`, `guide_available`, and `guide_element_count` fields. Older revisions without a catalog receive safe fallback values, preserving compatibility with older host apps and workspaces.
