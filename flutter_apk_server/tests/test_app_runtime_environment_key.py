import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

from flutter_apk_server.server import create_app, utc_now_iso


class AppRuntimeEnvironmentKeyTests(unittest.TestCase):
    def test_environment_key_is_used_without_being_persisted_or_logged(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            secret = "runtime-secret-test-only"
            task_id = "runtime-environment-key-task"
            package_name = "kr.ac.kangwon.hai.generated.runtime.environment"
            environment = {
                "BASE_PROJECT_PATH": str(root / "base"),
                "WORKSPACES_ROOT": str(root / "workspaces"),
                "DB_PATH": str(root / "tasks.db"),
                "APP_DATA_DB_PATH": str(root / "app_data.db"),
                "MOCK_CODEX": "1",
                "INTENT_AGENT_ENABLED": "0",
                "APP_RUNTIME_ENABLED": "1",
                "APP_RUNTIME_OPENAI_API_KEY": secret,
            }

            with patch.dict(os.environ, environment, clear=False):
                app = create_app()
                with (
                    patch(
                        "flutter_apk_server.server.invoke_app_runtime_model",
                        return_value={
                            "message": "runtime response",
                            "usage": {
                                "input_tokens": 5,
                                "output_tokens": 3,
                                "total_tokens": 8,
                            },
                            "raw_response": {"id": "response-test"},
                        },
                    ) as invoke_model,
                    TestClient(app) as client,
                ):
                    now = utc_now_iso()
                    app.state.db.create_task(
                        {
                            "task_id": task_id,
                            "user_id": "test-user",
                            "device_id": "test-device",
                            "prompt": "test",
                            "status": "Success",
                            "message": "ready",
                            "package_name": package_name,
                            "created_at": now,
                            "updated_at": now,
                        }
                    )
                    config_response = client.get(
                        f"/apps/{task_id}/llm-config",
                        params={"device_id": "test-device"},
                    )
                    self.assertEqual(200, config_response.status_code)
                    self.assertTrue(config_response.json()["api_key_configured"])

                    stored_before = app.state.db.get_app_llm_config(task_id)
                    self.assertEqual("", stored_before["api_key"])

                    response = client.post(
                        f"/apps/{task_id}/llm/respond",
                        json={
                            "package_name": package_name,
                            "user_message": "runtime request",
                            "context": "test context",
                        },
                    )

                    self.assertEqual(200, response.status_code, response.text)
                    self.assertEqual("runtime response", response.json()["message"])
                    effective_config = invoke_model.call_args.args[0]
                    self.assertEqual(secret, effective_config["api_key"])

                stored_after = app.state.db.get_app_llm_config(task_id)
                self.assertEqual("", stored_after["api_key"])
                serialized_events = json.dumps(
                    app.state.db.list_events(task_id),
                    ensure_ascii=False,
                )
                self.assertNotIn(secret, serialized_events)


if __name__ == "__main__":
    unittest.main()
