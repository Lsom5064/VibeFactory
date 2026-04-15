import os
import shutil
import subprocess
import uuid
import re
import json
from openai import OpenAI

# --- [1. 환경 설정] ---
API_KEY = os.environ.get("OPENAI_API_KEY")
BASE_PROJECT_PATH = "/Users/hai/Desktop/buildingAppwithLLMs_app/BaseProject"
BUILD_ROOT_DIR = "/Users/hai/Desktop/buildingAppwithLLMs_app/Builds"
BASE_PACKAGE_NAME = "kr.ac.kangwon.hai" # 베이스 경로만 지정

os.environ["ANDROID_HOME"] = "/Users/hai/Library/Android/sdk"
if not API_KEY:
    raise RuntimeError("OPENAI_API_KEY environment variable not set")
client = OpenAI(api_key=API_KEY)

# --- [2. 유틸리티 및 신뢰성 강화 함수] ---

def get_llm_json(system_prompt, user_prompt, retry_count=2):
    """JSON 추출 안정성을 강화한 LLM 호출 함수"""
    for i in range(retry_count):
        try:
            response = client.chat.completions.create(
                model="gpt-5.2",
                response_format={"type": "json_object"},
                messages=[
                    {"role": "system", "content": system_prompt + " \nIMPORTANT: Respond only in valid JSON format."},
                    {"role": "user", "content": user_prompt}
                ],
                temperature=0.1
            )

            usage = response.usage
            print("\n" + "─" * 40)
            print(f"🔹 [LLM Usage Report]")
            print(f"   - Input Tokens  : {usage.prompt_tokens}")
            print(f"   - Output Tokens : {usage.completion_tokens}")
            print(f"   - Total Tokens  : {usage.total_tokens}")
            print("─" * 40 + "\n")

            raw_content = response.choices[0].message.content
            
            # 마크다운 블록(```json) 제거 로직
            clean_json = re.sub(r"```json|```", "", raw_content).strip()
            data = json.loads(clean_json)
            
            # 필수 필드 유효성 검사 (필요 시 더 추가)
            if "files" not in data and "kotlin" not in raw_content:
                raise ValueError("Missing critical fields in LLM response")
                
            return data
        except Exception as e:
            if i == retry_count - 1:
                print(f"❌ LLM 최종 응답 실패: {e}")
                return {"error": str(e), "status": "failed"}
            print(f"⚠️ JSON 파싱 재시도 중... ({i+1})")

def save_metadata(project_path, metadata):
    """프로젝트 정체성 보존을 위한 메타데이터 저장 (vibe.json)"""
    meta_path = os.path.join(project_path, "vibe.json")
    with open(meta_path, "w", encoding='utf-8') as f:
        json.dump(metadata, f, indent=4)

def load_metadata(project_path):
    """기존 프로젝트 정보를 불러와 패키지 불일치 방지"""
    meta_path = os.path.join(project_path, "vibe.json")
    if os.path.exists(meta_path):
        with open(meta_path, "r", encoding='utf-8') as f:
            return json.load(f)
    return None

def safe_join(base, rel_path):
    full_path = os.path.abspath(os.path.join(base, rel_path))
    if not full_path.startswith(os.path.abspath(base)):
        raise ValueError("Unsafe path detected")
    return full_path

def get_current_project_snapshot(project_path, package_name):
    """자가 복구를 위한 현재 코드 스냅샷 추출"""
    pkg_path = package_name.replace(".", "/")
    important_files = [
        "app/src/main/AndroidManifest.xml",
        "app/build.gradle",
        "app/src/main/res/layout/activity_main.xml",
        f"app/src/main/java/{pkg_path}/MainActivity.kt"
    ]
    
    snapshot = []
    for rel_path in important_files:
        full_path = safe_join(project_path, rel_path)
        if os.path.exists(full_path):
            with open(full_path, "r", encoding="utf-8") as f:
                snapshot.append(f"[{rel_path}]\n{f.read()}")
    return "\n\n".join(snapshot)



def save_project_files(project_path, file_list):
    if not file_list: return
    for file_info in file_list:
        rel_path = file_info.get('path')
        content = file_info.get('content')
        if not rel_path or content is None: continue
        
        full_path = safe_join(project_path, rel_path)
        os.makedirs(os.path.dirname(full_path), exist_ok=True)
        with open(full_path, "w", encoding='utf-8') as f:
            f.write(content)

def run_flutter_build(project_path):
    # 1. 환경 변수 복사 (Flutter SDK 경로 포함 확인)
    current_env = os.environ.copy()
    
    print(f"🚀 [Flutter Build Start] Path: {project_path}")
    
    # 2. 플러터 빌드 실행 (APK 생성)
    process = subprocess.Popen(
        ["flutter", "build", "apk", "--debug"], 
        cwd=project_path, 
        stdout=subprocess.PIPE, 
        stderr=subprocess.PIPE, 
        text=True,
        env=current_env
    )
    
    stdout, stderr = process.communicate()
    
    # 3. 플러터의 APK 생성 표준 경로
    apk_file_path = os.path.join(project_path, "build/app/outputs/flutter-apk/app-debug.apk")

    if process.returncode != 0:
        full_error = (stderr + "\n" + stdout)[-2000:].strip()
        print(f"❌ Flutter Build Fail: {full_error[:500]}")
        return False, full_error
    
    if os.path.exists(apk_file_path):
        return True, apk_file_path
    else:
        return False, "APK file not found after successful build command."

# def run_gradle_build(project_path):
#     # 1. 실행 권한 부여
#     subprocess.run(["chmod", "+x", "gradlew"], cwd=project_path, capture_output=True)

#     # 2. 현재 서버의 환경 변수(ANDROID_HOME 등)를 그대로 복사
#     current_env = os.environ.copy()
    
#     # 3. 빌드 실행 (환경 변수 env=current_env 전달이 핵심!)
#     print(f"🚀 [Executing Gradle] Path: {project_path}")
#     process = subprocess.Popen(
#         ["./gradlew", "assembleDebug"], 
#         cwd=project_path, 
#         stdout=subprocess.PIPE, 
#         stderr=subprocess.PIPE, 
#         text=True,
#         env=current_env # 🌟 파이썬이 알고 있는 모든 환경 변수를 주입합니다.
#     )
    
#     stdout, stderr = process.communicate()
#     apk_file_path = os.path.join(project_path, "app/build/outputs/apk/debug/app-debug.apk")

#     # 4. 리턴 코드가 0이 아니더라도 APK가 생겼다면 성공으로 간주 (방어적 설계)
#     if process.returncode != 0:
#         if os.path.exists(apk_file_path):
#             print("⚠️ 빌드 프로세스는 경고를 뱉었지만, APK 생성에는 성공했습니다.")
#             return True, apk_file_path
        
#         # 진짜 실패했을 때 에러 로그 구성 (절대 None이 되지 않게 함)
#         error_msg = stderr.strip() if stderr.strip() else stdout[-1500:].strip()
#         if not error_msg:
#             error_msg = f"Gradle build failed with return code {process.returncode} but left no logs."
        
#         print(f"❌ 빌드 실패 상세: {error_msg[:500]}")
#         return False, error_msg

#     return True, apk_file_path

# --- [3. 메인 엔진: 상황 인식형 건축가] ---

def run_vibe_factory(task_id, user_request, device_context=None, callback_log=None):
    if callback_log: callback_log("🏗️ 상황 인식형 AI가 기기 환경에 맞춤 설계 중...")
    
    unique_id = uuid.uuid4().hex[:6]
    
    # system_prompt = f"""
    # You are a Flutter Expert. Build a STABLE Flutter app using Dart.

    # [Target Device Context]
    # {json.dumps(device_context, indent=2) if device_context else "Standard Android Device"}

    # ==============================
    # IDENTITY RULES
    # ==============================
    # 1. PACKAGE NAME:
    # Must be: kr.ac.kangwon.hai.[appname_lowercase_no_spaces]

    # 2. The SAME package name MUST be used in:
    # - android {{ namespace "..." }}
    # - defaultConfig {{ applicationId "..." }}
    # - Kotlin package declaration
    # - R import

    # 3. You MUST explicitly write:
    # import kr.ac.kangwon.hai.[appname].R

    # 4. TASK ID (For Tracing): {task_id}

    # ==============================
    # CRASH REPORTING (CRITICAL)
    # ==============================
    # Every app MUST include a global crash handler in MainActivity's onCreate.
    # This allows the system to fix runtime errors automatically.

    # 1. Use: Thread.setDefaultUncaughtExceptionHandler {{ _, throwable -> ... }}
    # 2. Inside the handler:
    #    - Capture the full stack trace using Log.getStackTraceString(throwable).
    #    - Send a Broadcast Intent:
    #      * Action: "kr.ac.kangwon.hai.action.CRASH_REPORT"
    #      * Extra "package_name": (The current app's package name)
    #      * Extra "stack_trace": (The captured stack trace)
    #      * Extra "task_id": "{task_id}"
    #    - After sending the broadcast, call android.os.Process.killProcess(android.os.Process.myPid()) and exitProcess(10).

    # ==============================
    # GRADLE STRICT RULES (AGP 8+)
    # ==============================

    # CRITICAL: The project uses Groovy DSL (build.gradle), NOT Kotlin DSL.

    # The android block MUST follow EXACTLY this structure:

    # android {{
    #     namespace "kr.ac.kangwon.hai.[appname]"

    #     compileSdk 34

    #     defaultConfig {{
    #         applicationId "kr.ac.kangwon.hai.[appname]"
    #         minSdk 24
    #         targetSdk 34
    #         versionCode 1
    #         versionName "1.0"
    #     }}

    #     compileOptions {{
    #         sourceCompatibility JavaVersion.VERSION_1_8
    #         targetCompatibility JavaVersion.VERSION_1_8
    #     }}

    #     kotlinOptions {{
    #         jvmTarget = "1.8"
    #     }}
    # }}

    # RULES:
    # - DO NOT use '=' for compileSdk, minSdk, targetSdk.
    # - DO NOT use Kotlin DSL syntax.
    # - DO NOT remove existing required blocks.
    # - Only modify dependencies if necessary.
    # - All dependency versions MUST be explicit literal strings.
    # - CRITICAL: In MainActivity.kt, the 'package' line MUST be exactly the same as 'package_name' in JSON.
    # - CRITICAL: Do NOT omit any required imports (e.g., android.os.Bundle, androidx.appcompat.app.AppCompatActivity).

    # ==============================
    # DEPENDENCIES (STABLE SET)
    # ==============================

    # If needed, use ONLY:

    # implementation "androidx.appcompat:appcompat:1.6.1"
    # implementation "com.google.android.material:material:1.9.0"
    # implementation "androidx.constraintlayout:constraintlayout:2.1.4"
    # implementation "androidx.core:core-ktx:1.10.1"

    # DO NOT use Material3.
    # DO NOT use unknown libraries.

    # ==============================
    # RESOURCE RULES
    # ==============================

    # - You MUST create res/values/styles.xml.
    # - You MUST define a theme that inherits from Theme.AppCompat.Light.NoActionBar.
    # - You MUST set android:theme in AndroidManifest.xml to that theme.
    # - If you reference a custom style, you MUST create styles.xml.
    # - Do NOT reference resources that do not exist.
    # - Android 12+: Activities with intent-filter MUST include android:exported="true".
    # If styles.xml is missing, the app is considered invalid.

    # ==============================
    # CONTEXT-AWARE LOGIC
    # ==============================
    # If sensors or hardware are mentioned in Device Context,
    # you may use them but ensure proper permissions in Manifest.

    # ==============================
    # FUNCTIONAL REQUIREMENTS
    # ==============================
    # - The app MUST contain visible UI elements.
    # - The layout MUST include at least:
    #     - One Button
    #     - One EditText
    #     - One TextView or RecyclerView
    # - All views MUST have android:id.
    # - If a Button is added, it MUST have a working click listener in MainActivity.
    # - The app MUST perform a visible action when the button is clicked.

    # ==============================
    # OUTPUT FORMAT
    # ==============================
    # Return ONLY valid JSON:

    # {{
    #     "title": "...",
    #     "package_name": "...",
    #     "files": [
    #         {{ "path": "...", "content": "..." }}
    #     ]
    # }}
    # """
    
    system_prompt = f"""
        You are a Flutter & Dart Expert. Your mission is to build a STABLE, PRODUCTION-READY Flutter app that integrates with a Self-Healing System.

        [Target Device Context]
        {json.dumps(device_context, indent=2) if device_context else "Standard Android Device"}

        ============================================================
        STEP-BY-STEP PLANNING (Chain-of-Thought)
        ============================================================
        Before providing the final JSON, you must internally:
        1. Analyze the User Intent and Device Context.
        2. Design a Widget Tree: Identify needed State (Stateful vs Stateless).
        3. Plan the Error Handling: How to catch both Flutter-level and Dart-level exceptions.
        4. Mapping IDs: Ensure every interactive widget has a unique 'Key' for identification.

        ============================================================
        IDENTITY & TRACING RULES
        ============================================================
        1. PACKAGE NAME: kr.ac.kangwon.hai.[appname_lowercase_no_spaces]
        2. TASK ID (Tracing ID): {task_id}
        3. Project Name (in pubspec.yaml): baseproject
        
        ============================================================
        CRASH REPORTING & SELF-HEALING (CRITICAL)
        ============================================================
        The app must scream (Report Crash) before it dies. This is done via MethodChannel to the Android Host.

        1. IMPLEMENT A GLOBAL ERROR HANDLER in lib/main.dart:
        - Use 'FlutterError.onError' for Flutter-specific errors.
        - Use 'PlatformDispatcher.instance.onError' for asynchronous Dart errors.
        2. COMMUNICATION:
        - Use MethodChannel name: "kr.ac.kangwon.hai/crash"
        - Method Name: "reportCrash"
        - Arguments: {{
            "package_name": "kr.ac.kangwon.hai.[appname]",
            "stack_trace": details.exceptionAsString(),
            "task_id": "{task_id}"
            }}
        3. ANDROID HOST: Assume the MainActivity.kt in the Android folder is already configured to catch this MethodChannel call and send the 'kr.ac.kangwon.hai.action.CRASH_REPORT' broadcast.

        ============================================================
        FLUTTER CODING STANDARDS
        ============================================================
        1. LANGUAGE: Dart 3.x (Null Safety is MANDATORY).
        2. UI FRAMEWORK: Material Design (Use Material2-style widgets for stability).
        3. SCROLLING: If content exceeds screen height, the ENTIRE view must be scrollable (use SingleChildScrollView).
        4. INPUT: TextFields must support multiline if needed and have proper 'decoration'.

        ============================================================
        STRICT WIDGET REQUIREMENTS
        ============================================================
        Every generated app MUST contain:
        - At least one 'ElevatedButton' with a working 'onPressed' logic.
        - At least one 'TextField' with a 'TextEditingController'.
        - At least one 'Text' or 'ListView' to display data.
        - VISIBLE ACTION: When a button is clicked, there must be a visible UI change (e.g., updating a Text widget or showing a SnackBar).

        ============================================================
        ANDROID-SPECIFIC CONFIGURATION (pubspec.yaml & Gradle)
        ============================================================
        - If specific permissions (Sensors, Camera) are needed based on Device Context, you MUST include them in 'android/app/src/main/AndroidManifest.xml'.
        - For Android 12+, ensure 'android:exported="true"' is set for the Main Activity.

        ============================================================
        OUTPUT FORMAT (JSON ONLY)
        ============================================================
        Return ONLY valid JSON. Focus on 'lib/main.dart' as the primary logic file.

        {{
            "title": "...",
            "package_name": "kr.ac.kangwon.hai.[appname]",
            "files": [
                {{
                    "path": "lib/main.dart",
                    "content": "..."
                }},
                {{
                    "path": "pubspec.yaml",
                    "content": "..."
                }}
            ]
        }}
    """

    # 1. 초기 설계
    response_data = get_llm_json(system_prompt, f"User Intent: {user_request}")
    app_title = response_data.get("title", "VibeApp")
    pkg_name = response_data.get("package_name", f"kr.ac.kangwon.hai.app_{unique_id}")
    
    folder_name = re.sub(r'\W+', '', app_title) + "_" + unique_id
    project_path = os.path.join(BUILD_ROOT_DIR, folder_name)
    
    if os.path.exists(project_path): shutil.rmtree(project_path)
    shutil.copytree(BASE_PROJECT_PATH, project_path)

    java_root = os.path.join(
        project_path,
        "app/src/main/java/kr/ac/kangwon/hai"
    )

    if os.path.exists(java_root):
        shutil.rmtree(java_root)

    os.makedirs(java_root, exist_ok=True)

    # 🌟 해결책 반영: vibe.json 메타데이터 저장
    save_metadata(project_path, {
        "app_title": app_title, 
        "package_name": pkg_name, 
        "task_id": task_id
        })
    save_project_files(project_path, response_data.get("files", []))

    # 2. 고도화된 자가 치유 루프 (스냅샷 + 에러 로그)
    for i in range(3):
        if callback_log: callback_log(f"⚙️ 빌드 및 검증 중... ({i+1}/3)")
        success, result = run_flutter_build(project_path)
        
        if success:
            return {"status": "success", "app_name": app_title, "apk_path": result, "project_path": project_path,"package_name": pkg_name}
        
        # 🌟 해결책 반영: 현재 코드 스냅샷을 포함하여 피드백
        current_code = get_current_project_snapshot(project_path, pkg_name)
        fix_prompt = f"""
        BUILD FAILED! 
        [Error Log]
        {result[-1000:]}

        [Current Code Snapshot]
        {current_code}

        Analyze the error and the current code, then provide the fixed "files" list.
        """
        fix_data = get_llm_json(system_prompt, fix_prompt)
        save_project_files(project_path, fix_data.get("files", []))

    return {"status": "failed", "error_log": result, "app_name": app_title, "project_path": project_path}

# --- [4. 수정 엔진: 패키지 정체성 보존형] ---

def refine_vibe_app(project_path, feedback, callback_log=None):
    if callback_log: callback_log("🔄 프로젝트 메타데이터 분석 및 수정 설계 중...")
    
    # 🌟 해결책 반영: vibe.json 로드하여 패키지명 고정
    metadata = load_metadata(project_path)
    pkg_name = metadata.get("package_name") if metadata else "kr.ac.kangwon.hai.baseproject"
    
    current_code = get_current_project_snapshot(project_path, pkg_name)
    
    system_prompt = f"""
    You are an Android Refiner. Update the project while keeping the ID identity.
    [Project Identity]
    Package Name: {pkg_name}
    (Ensure all R imports and package declarations match this!)

    [Current Code]
    {current_code}

    Based on user feedback, provide ONLY the modified files in JSON format.
    """
    
    refine_data = get_llm_json(system_prompt, f"Feedback: {feedback}")
    save_project_files(project_path, refine_data.get("files", []))

    # 수정 버전 빌드 루프
    for i in range(3):
        if callback_log: callback_log(f"⚙️ 수정 버전 빌드 중... ({i+1}/3)")
        success, result = run_flutter_build(project_path)
        if success:
            return {"status": "success", "apk_path": result, "project_path": project_path}
        
        # 자가 치유 (스냅샷 포함)
        snapshot = get_current_project_snapshot(project_path, pkg_name)
        fix_data = get_llm_json(system_prompt, f"Fix error: {result[-800:]}\nCurrent Code:\n{snapshot}")
        save_project_files(project_path, fix_data.get("files", []))

    return {"status": "failed", "error_log": result, "project_path": project_path,"package_name": pkg_name}
