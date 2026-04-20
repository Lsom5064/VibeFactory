"""
1단계 앱 생성 테스트 — 코드 생성 + 실제 데이터 fetching 확인
OPENAI_API_KEY 환경변수 필요.

사용법:
  python3 Server/test_generate.py 강원대학식
  python3 Server/test_generate.py 미세먼지
  python3 Server/test_generate.py 긱뉴스
  python3 Server/test_generate.py all   # 3개 연속
"""

import sys
import os
import glob

sys.path.insert(0, os.path.dirname(__file__))
from vibe_factory import run_vibe_factory

APPS = {
    "강원대학식": {
        "request": "강원대학교 오늘 학식 메뉴를 보여주는 앱 만들���줘. 학식 데이터를 인터넷에서 실시간으로 가져와야 해. URL은 직접 검색해서 찾아.",
        "must_contain_any": ["http", "dio", "get(", "fetch", "Uri.parse"],
        "must_not_contain": ["placeholder", "TODO: 실제", "dummy"],
    },
    "미세먼지": {
        "request": "현재 미세먼�� 정보를 실시간으로 보여주는 앱 만들어줘. 적절한 공공 API를 직접 검색해서 찾아서 사용해.",
        "must_contain_any": ["http", "dio", "get(", "Uri.parse", "API"],
        "must_not_contain": ["placeholder", "TODO: 실제"],
    },
    "긱뉴스": {
        "request": "Hacker News 최신 뉴스를 보여주는 앱 만들어줘. HN API URL은 직접 검색해서 찾아.",
        "must_contain_any": ["http", "dio", "hacker-news", "hackernews", "firebase", "get("],
        "must_not_contain": ["placeholder", "TODO: 실제"],
    },
}


def check_generated_code(project_path: str, checks: dict) -> dict:
    """lib/ 아래 dart 파일들을 스캔해서 데이터 fetching 코드 확인."""
    lib_path = os.path.join(project_path, "lib")
    if not os.path.exists(lib_path):
        return {"ok": False, "reason": "lib/ 폴더 없음"}

    dart_files = glob.glob(os.path.join(lib_path, "**/*.dart"), recursive=True)
    all_code = ""
    for f in dart_files:
        try:
            with open(f, "r", encoding="utf-8") as fp:
                all_code += fp.read() + "\n"
        except Exception:
            pass

    found_any = [kw for kw in checks["must_contain_any"] if kw.lower() in all_code.lower()]
    found_bad = [kw for kw in checks["must_not_contain"] if kw.lower() in all_code.lower()]

    if not found_any:
        return {
            "ok": False,
            "reason": f"실제 데이터 fetching 코드 없음 (찾은 키워드 없음: {checks['must_contain_any']})",
            "dart_files": len(dart_files),
        }
    if found_bad:
        return {
            "ok": False,
            "reason": f"placeholder 코드 발견: {found_bad}",
            "dart_files": len(dart_files),
        }

    return {
        "ok": True,
        "reason": f"실제 fetching 코드 확인: {found_any}",
        "dart_files": len(dart_files),
    }


def run_test(name: str):
    app = APPS[name]
    task_id = f"test_{name}"
    logs = []

    print(f"\n{'='*60}")
    print(f"테스트: {name}")
    print(f"요청: {app['request'][:80]}...")
    print(f"{'='*60}")

    result = run_vibe_factory(
        task_id=task_id,
        user_request=app["request"],
        callback_log=lambda msg: (logs.append(msg), print(f"  {msg}")),
    )

    print(f"\n결과 status: {result.get('status')}")

    if result.get("status") != "success":
        print(f"❌ 실패: {result.get('error_log', result)[:300]}")
        return False

    project_path = result.get("project_path", "")
    code_check = check_generated_code(project_path, app)

    if code_check["ok"]:
        print(f"✅ 코드 확인 통과: {code_check['reason']}")
        print(f"   Dart 파일 수: {code_check['dart_files']}")
    else:
        print(f"❌ 코드 확인 실패: {code_check['reason']}")
        print(f"   Dart 파일 수: {code_check.get('dart_files', 0)}")

    return code_check["ok"]


if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else "강원대학식"

    if target == "all":
        results = {}
        for name in APPS:
            results[name] = run_test(name)
        print(f"\n{'='*60}")
        print("최종 결과:")
        for name, ok in results.items():
            print(f"  {'✅' if ok else '❌'} {name}")
    elif target in APPS:
        run_test(target)
    else:
        print(f"알 수 없는 앱: {target}")
        print(f"사용 가능: {list(APPS.keys())} 또는 all")
