import unittest

from flutter_apk_server.prebuild_requirements import (
    client_build_environment_for_requirements,
    extract_participant_credential,
    format_prebuild_requirements,
    missing_blocking_requirements,
    pending_participant_credential_requirement,
    resolve_prebuild_requirements,
)


class PrebuildRequirementsTests(unittest.TestCase):
    def test_google_maps_request_is_blocked_until_key_is_registered(self) -> None:
        requirements = resolve_prebuild_requirements(
            "Google Maps와 Places API로 주변 카페를 보여주는 앱",
            environment={},
            package_name="kr.example.cafes",
        )

        self.assertEqual(["google_maps_platform"], [item["id"] for item in requirements])
        self.assertFalse(requirements[0]["configured"])
        self.assertEqual(1, len(missing_blocking_requirements(requirements)))
        guidance = format_prebuild_requirements(requirements)
        self.assertIn("담당 연구원 설정 필요", guidance)
        self.assertIn("담당 연구원에게 문의", guidance)
        self.assertNotIn("Google Cloud 프로젝트", guidance)
        self.assertNotIn("배포 서명 SHA", guidance)
        self.assertNotIn("kr.example.cafes", guidance)

    def test_key_without_android_package_registration_remains_blocked(self) -> None:
        requirements = resolve_prebuild_requirements(
            "구글 지도와 Places SDK를 사용하는 앱",
            environment={"GOOGLE_MAPS_API_KEY": "registered-secret"},
            package_name="kr.example.cafes",
        )

        self.assertFalse(requirements[0]["configured"])
        self.assertTrue(requirements[0]["credentials_configured"])
        self.assertFalse(requirements[0]["package_registered"])
        self.assertIn("담당 연구원 설정 필요", format_prebuild_requirements(requirements))

    def test_registered_client_key_is_only_returned_for_final_build(self) -> None:
        environment = {
            "GOOGLE_MAPS_API_KEY": "registered-secret",
            "GOOGLE_MAPS_ALLOWED_PACKAGES": "kr.example.other, kr.example.cafes",
        }
        requirements = resolve_prebuild_requirements(
            "구글 지도와 Places SDK를 사용하는 앱",
            environment=environment,
            package_name="kr.example.cafes",
        )

        self.assertTrue(requirements[0]["configured"])
        self.assertEqual([], missing_blocking_requirements(requirements))
        self.assertEqual(
            {"GOOGLE_MAPS_API_KEY": "registered-secret"},
            client_build_environment_for_requirements(
                requirements,
                environment=environment,
            ),
        )
        self.assertNotIn("registered-secret", format_prebuild_requirements(requirements))

    def test_unknown_special_permission_is_advisory_not_secret_registration(self) -> None:
        requirements = resolve_prebuild_requirements(
            "알림 접근 권한으로 다른 앱의 알림을 정리하는 앱",
            [
                {
                    "id": "notification_listener_access",
                    "title": "알림 접근 권한",
                    "type": "special_permission",
                    "reason": "사용자가 시스템 설정에서 별도로 허용해야 합니다.",
                    "blocking": False,
                    "execution_location": "device_settings",
                    "setup_steps": ["앱 설치 후 알림 접근 설정에서 허용합니다."],
                    "setup_url": "",
                    "security_note": "알림 내용에는 개인정보가 포함될 수 있습니다.",
                }
            ],
            environment={},
        )

        self.assertEqual("notification_listener_access", requirements[0]["id"])
        self.assertTrue(requirements[0]["configured"])
        self.assertEqual([], missing_blocking_requirements(requirements))
        guidance = format_prebuild_requirements(requirements)
        self.assertIn("확인 필요", guidance)
        self.assertIn("알림 접근 설정에서 허용", guidance)

    def test_participant_can_register_simple_api_key_in_chat(self) -> None:
        requirements = resolve_prebuild_requirements(
            "OpenAI API를 이용한 AI 상담 앱",
            environment={},
            package_name="kr.example.chat",
        )

        requirement = pending_participant_credential_requirement(requirements)

        self.assertIsNotNone(requirement)
        assert requirement is not None
        self.assertEqual("participant", requirement["setup_owner"])
        self.assertEqual("APP_RUNTIME_OPENAI_API_KEY", requirement["participant_credential_environment"])
        guidance = format_prebuild_requirements(requirements)
        self.assertIn("API 키 입력 필요", guidance)
        self.assertIn("이 채팅에 입력", guidance)
        self.assertEqual(
            "sk-test_1234567890",
            extract_participant_credential("API 키: sk-test_1234567890", requirement),
        )
        self.assertEqual("", extract_participant_credential("키를 어디서 발급받나요?", requirement))

    def test_participant_key_marks_requirement_ready_and_is_injected_only_at_build(self) -> None:
        environment = {"OPENWEATHER_API_KEY": "configured-unit-test-value"}
        requirements = resolve_prebuild_requirements(
            "실시간 날씨 예보 앱",
            environment=environment,
        )

        self.assertTrue(requirements[0]["configured"])
        self.assertEqual([], missing_blocking_requirements(requirements))
        self.assertEqual(
            environment,
            client_build_environment_for_requirements(requirements, environment=environment),
        )
        self.assertNotIn(environment["OPENWEATHER_API_KEY"], format_prebuild_requirements(requirements))


if __name__ == "__main__":
    unittest.main()
