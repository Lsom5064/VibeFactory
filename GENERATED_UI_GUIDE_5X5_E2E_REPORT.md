# Generated App UI Guide 5x5 E2E Report

## Test scope

- Date: 2026-09-07 (Asia/Seoul)
- Device: Samsung SM-S908N, Android API 36
- Generation engine: actual Codex (`MOCK_CODEX=0`)
- Persona: Android applications and development terminology are unfamiliar to a nondeveloper in their 50s
- Passes: baseline 5 apps + 25 XML revisions, improved build 5 apps + 25 XML revisions
- Required checks: APK build, update installation, launch, first-run guide, guide replay, XML change, package continuity, crash absence

## Scenarios

| Key | App | Improved-pass task ID | Final revision |
| --- | --- | --- | --- |
| medication | 복약체크 | `309682a22f98457c9ab08c4af016c194` | `rev_0006` |
| shopping | 장보기메모 | `60b96832bb2b418e8f00628a2000b4fc` | `rev_0006` |
| walking | 산책기록 | `70df61baa0f44996974962a2782d0a4a` | `rev_0006` |
| meeting | 모임일정판 | `8e3c78a7b2a24961b80be7b62024b40e` | `rev_0006` |
| water | 물한잔 | `f022e29d7c464a9db6573b67a7cf4a75` | `rev_0006` |

## Revision type counts

Each Task received the same five revisions. This makes comparisons between apps and passes reproducible.

| Task | Title style | Move | Delete | Touch/accessibility | Readability/spacing | Total |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 복약체크 | 1 | 1 | 1 | 1 | 1 | 5 |
| 장보기메모 | 1 | 1 | 1 | 1 | 1 | 5 |
| 산책기록 | 1 | 1 | 1 | 1 | 1 | 5 |
| 모임일정판 | 1 | 1 | 1 | 1 | 1 | 5 |
| 물한잔 | 1 | 1 | 1 | 1 | 1 | 5 |
| Total | 5 | 5 | 5 | 5 | 5 | 25 |

The editor protocol groups these as `behavior` 3 times, `move` once, and `delete` once per Task. Across five Tasks this is `behavior` 15, `move` 5, and `delete` 5.

## Baseline findings

1. Closing the guide could change the user's scroll position.
2. Guide text was small for the target persona and primary and secondary actions were not visually distinct.
3. The replay control used an unexplained `?` symbol.
4. Restoring protected runtime files could overwrite app-specific startup code placed in `GeneratedApplication`.
5. Generated work repeatedly ran Git and overlapping Gradle checks, increasing elapsed time and token use.
6. Revision prompts did not state the exact `guideVersion`, so server-side catalog repair was more likely.
7. Running status could expose a stale build stage from an earlier timeline event.
8. Growing lists were not explicitly required to use `RecyclerView`.

## Changes

- The guide now restores scroll coordinates and RecyclerView layout state after dismissal.
- Guide typography, button hierarchy, touch target sizes, and replay wording were improved.
- Added `GeneratedAppInitializer` as the preserved app-specific startup extension point.
- Generation instructions now prohibit irrelevant Git commands and redundant Gradle builds, and prescribe one lint command.
- Initial and revision prompts now require the exact revision in `vf_ui_catalog.xml`.
- Running status uses the current task message instead of stale timeline data.
- Growing lists explicitly require `RecyclerView` and an adapter.
- Unit and connected-device tests cover these contracts.

## Improved-pass result

All five initial generations and all 25 XML revisions passed. Every revision produced an APK, retained the Task package name, installed as an update, launched, displayed and completed the guide, retained the replay control, applied the intended XML change, and produced no fatal crash.

| Check | Result |
| --- | ---: |
| Initial generations | 5/5 |
| XML revisions | 25/25 |
| APK download/install/launch | 30/30 |
| First-run guide and completion | 30/30 |
| Guide replay control | 30/30 |
| Intended XML change | 25/25 |
| Stable package per Task | 25/25 revisions |
| Fatal crashes | 0 |

Three intermediate records marked failed were verifier false positives: one selected a `gone` catalog element and two retried an already acknowledged guide version. The verifier was corrected to select a visible catalog target and the complete rerun passed; these were not application failures.

## Performance

`total_tokens` includes cached input tokens and should not be interpreted directly as API cost.

| Scope | Metric | Baseline | Improved | Change |
| --- | --- | ---: | ---: | ---: |
| Initial generation, n=5 | Mean elapsed time | 669.8 s | 545.2 s | -18.6% |
| Initial generation, n=5 | Mean total tokens | 1,935,079 | 951,131 | -50.8% |
| Initial generation, n=5 | Mean uncached input | 60,852 | 41,956 | -31.1% |
| Initial generation, n=5 | Mean output | 26,060 | 21,674 | -16.8% |
| Revision, n=25 | Mean elapsed time | 180.1 s | 167.1 s | -7.2% |
| Revision, n=25 | Mean total tokens | 782,390 | 572,651 | -26.8% |
| Revision, n=25 | Mean uncached input | 44,563 | 34,098 | -23.5% |
| Revision, n=25 | Mean output | 4,904 | 4,297 | -12.4% |
| All artifacts, n=30 | Mean elapsed time | 261.7 s | 230.1 s | -12.1% |
| All artifacts, n=30 | Mean total tokens | 974,505 | 635,731 | -34.8% |

Improved initial generation ranged from 429 to 666 seconds. Improved revisions ranged from 139 to 296 seconds.

## Automated verification

- Server tests: 111 passed.
- BaseProject lint, unit tests, debug build, and Android-test compilation: passed.
- Connected Android test on SM-S908N: 1 passed.
- Python syntax compilation and Git whitespace checks: passed.

## Evidence

- Baseline structured results: `/private/tmp/vf_guide_e2e_20260907/e2e_results.jsonl`
- Improved structured results: `/private/tmp/vf_guide_e2e_after_20260907/e2e_results.jsonl`
- Improved screenshots and device evidence: `/private/tmp/vf_guide_e2e_after_20260907/evidence/`

## Remaining noncritical observations

- 물한잔 generated its own top-level `사용법 다시 보기` action in addition to the common floating `사용법` control. This is redundant but does not block the guide.
- Four initial generations needed automatic catalog repair for dynamic item layouts; all 25 subsequent revisions authored valid catalogs without repair.
- Some generated layouts use tight outer margins. They remain readable and functional, but a future visual-quality rule could enforce a minimum page gutter.

No critical guide, XML-revision, installation, package-continuity, crash, or interaction-delay defect remained after the second full pass.
