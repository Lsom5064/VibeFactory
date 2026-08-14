import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from flutter_apk_server.server import AppDataDatabase, Database, utc_now_iso


def task_payload(task_id: str) -> dict[str, object]:
    now = utc_now_iso()
    return {
        "task_id": task_id,
        "user_id": "test-user",
        "device_id": "test-device",
        "prompt": "test",
        "status": "Success",
        "message": "ready",
        "created_at": now,
        "updated_at": now,
    }


class DatabaseIntegrityTests(unittest.TestCase):
    def test_connections_enable_foreign_keys_and_invalid_relations_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            database = Database(Path(temp_dir) / "tasks.db")
            database.init_db()
            database.create_task(task_payload("task-a"))
            database.create_task(task_payload("task-b"))
            event_id = database.log_event(
                "task-a",
                actor="user",
                event_type="user_message",
                message_text="hello",
            )

            with database.connect() as connection:
                self.assertEqual(1, int(connection.execute("PRAGMA foreign_keys").fetchone()[0]))
                self.assertTrue(database.relation_schema_is_current(connection))
                self.assertEqual([], connection.execute("PRAGMA foreign_key_check").fetchall())

            with self.assertRaises(sqlite3.IntegrityError):
                database.log_event(
                    "missing-task",
                    actor="system",
                    event_type="invalid",
                )
            with self.assertRaises(sqlite3.IntegrityError):
                database.record_task_attachment(
                    task_id="task-b",
                    event_id=event_id,
                    source="test",
                    kind="image",
                    original_name="image.jpg",
                    mime_type="image/jpeg",
                    workspace_path="reference_images/image.jpg",
                    absolute_path="/tmp/image.jpg",
                    size_bytes=1,
                    sha256="hash",
                    status="saved",
                )
            with database.connect() as connection:
                with self.assertRaises(sqlite3.IntegrityError):
                    connection.execute("DELETE FROM tasks WHERE task_id = ?", ("task-a",))

    def test_generated_primary_keys_retry_only_primary_key_collisions(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            database = Database(Path(temp_dir) / "tasks.db")
            database.init_db()
            database.create_task(task_payload("task-a"))
            with database.connect() as connection:
                connection.execute(
                    """
                    INSERT INTO task_events (
                        event_id, task_id, actor, event_type, message_text, payload_json, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    ("duplicate-id", "task-a", "system", "existing", "", None, utc_now_iso()),
                )
                connection.commit()

            with patch(
                "flutter_apk_server.server.new_database_id",
                side_effect=["duplicate-id", "replacement-id"],
            ) as id_factory:
                event_id = database.log_event(
                    "task-a",
                    actor="user",
                    event_type="user_message",
                    message_text="retry",
                )

            self.assertEqual("replacement-id", event_id)
            self.assertEqual(2, id_factory.call_count)

            with patch(
                "flutter_apk_server.server.new_database_id",
                return_value="unused-id",
            ) as id_factory:
                with self.assertRaises(sqlite3.IntegrityError):
                    database.log_event(
                        "missing-task",
                        actor="system",
                        event_type="invalid",
                    )
            self.assertEqual(1, id_factory.call_count)

    def test_generated_task_id_rebuilds_task_after_collision(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            database = Database(Path(temp_dir) / "tasks.db")
            database.init_db()
            database.create_task(task_payload("duplicate-task"))
            built_ids: list[str] = []

            def build_task(task_id: str) -> dict[str, object]:
                built_ids.append(task_id)
                return task_payload(task_id)

            with patch(
                "flutter_apk_server.server.new_database_id",
                side_effect=["duplicate-task", "replacement-task"],
            ):
                task_id, task = database.create_task_with_generated_id(build_task)

            self.assertEqual("replacement-task", task_id)
            self.assertEqual("replacement-task", task["task_id"])
            self.assertEqual(["duplicate-task", "replacement-task"], built_ids)

    def test_app_data_record_id_retries_collision(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            database = AppDataDatabase(Path(temp_dir) / "app_data.db")
            database.init_db()
            with database.connect() as connection:
                connection.execute(
                    """
                    INSERT INTO app_data_records (
                        record_id, task_id, package_name, collection, owner_id,
                        data_json, created_at, updated_at, deleted_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)
                    """,
                    (
                        "duplicate-record",
                        "task-a",
                        "example.app",
                        "items",
                        None,
                        "{}",
                        utc_now_iso(),
                        utc_now_iso(),
                    ),
                )
                connection.commit()

            with patch(
                "flutter_apk_server.server.new_database_id",
                side_effect=["duplicate-record", "replacement-record"],
            ):
                record = database.create_record(
                    task_id="task-a",
                    package_name="example.app",
                    collection="items",
                    owner_id="",
                    data={"value": 1},
                )

            self.assertEqual("replacement-record", record["record_id"])

    def test_legacy_schema_migration_preserves_rows(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            database = Database(Path(temp_dir) / "tasks.db")
            database.init_db()
            database.create_task(task_payload("task-a"))
            event_id = database.log_event(
                "task-a",
                actor="user",
                event_type="user_message",
                message_text="preserve me",
            )
            database.record_task_attachment(
                task_id="task-a",
                event_id=event_id,
                source="test",
                kind="image",
                original_name="image.jpg",
                mime_type="image/jpeg",
                workspace_path="reference_images/image.jpg",
                absolute_path="/tmp/image.jpg",
                size_bytes=1,
                sha256="hash",
                status="saved",
            )
            database.record_project_snapshot(
                task_id="task-a",
                revision_label="rev_0001",
                source="new_app",
                workspace_path="/tmp/workspace",
                project_path="/tmp/workspace/project",
                request_summary="initial",
            )
            expected_counts: dict[str, int] = {}
            with sqlite3.connect(database.db_path) as connection:
                connection.execute("PRAGMA foreign_keys=OFF")
                for table_name, columns in database.RELATION_TABLE_COLUMNS.items():
                    expected_counts[table_name] = int(
                        connection.execute(f'SELECT COUNT(*) FROM "{table_name}"').fetchone()[0]
                    )
                    column_list = ", ".join(f'"{column}"' for column in columns)
                    legacy_name = f"{table_name}__legacy"
                    connection.execute(
                        f'CREATE TABLE "{legacy_name}" AS SELECT {column_list} FROM "{table_name}"'
                    )
                    connection.execute(f'DROP TABLE "{table_name}"')
                    connection.execute(f'ALTER TABLE "{legacy_name}" RENAME TO "{table_name}"')
                connection.commit()

            database.init_db()

            backup_path = database.db_path.with_name(
                f"{database.db_path.name}.pre_relation_fk_v1.bak"
            )
            self.assertTrue(backup_path.is_file())
            with sqlite3.connect(backup_path) as backup_connection:
                self.assertEqual(
                    "ok",
                    str(backup_connection.execute("PRAGMA integrity_check").fetchone()[0]),
                )
            with database.connect() as connection:
                self.assertTrue(database.relation_schema_is_current(connection))
                self.assertEqual([], connection.execute("PRAGMA foreign_key_check").fetchall())
                for table_name, expected_count in expected_counts.items():
                    actual_count = int(connection.execute(f'SELECT COUNT(*) FROM "{table_name}"').fetchone()[0])
                    self.assertEqual(expected_count, actual_count, table_name)
                preserved = connection.execute(
                    "SELECT message_text FROM task_events WHERE event_id = ?",
                    (event_id,),
                ).fetchone()
                self.assertEqual("preserve me", preserved[0])

    def test_legacy_orphan_stops_migration_without_deleting_the_row(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            database = Database(Path(temp_dir) / "tasks.db")
            database.init_db()
            columns = database.RELATION_TABLE_COLUMNS["task_events"]
            column_list = ", ".join(f'"{column}"' for column in columns)
            with sqlite3.connect(database.db_path) as connection:
                connection.execute("PRAGMA foreign_keys=OFF")
                connection.execute(
                    f'CREATE TABLE task_events__legacy AS SELECT {column_list} FROM task_events'
                )
                connection.execute("DROP TABLE task_events")
                connection.execute("ALTER TABLE task_events__legacy RENAME TO task_events")
                connection.execute(
                    """
                    INSERT INTO task_events (
                        event_id, task_id, actor, event_type, message_text, payload_json, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    ("orphan-event", "missing-task", "system", "test", "preserve", None, utc_now_iso()),
                )
                connection.commit()

            with self.assertRaisesRegex(RuntimeError, "task_events.task_id=1"):
                database.init_db()

            with sqlite3.connect(database.db_path) as connection:
                orphan = connection.execute(
                    "SELECT message_text FROM task_events WHERE event_id = ?",
                    ("orphan-event",),
                ).fetchone()
            self.assertEqual("preserve", orphan[0])

    def test_snapshot_revision_is_upserted_without_duplicate_rows(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            database = Database(Path(temp_dir) / "tasks.db")
            database.init_db()
            database.create_task(task_payload("task-a"))
            for summary in ("first", "latest"):
                database.record_project_snapshot(
                    task_id="task-a",
                    revision_label="rev_0001",
                    source="new_app",
                    workspace_path="/tmp/workspace",
                    project_path="/tmp/workspace/project",
                    request_summary=summary,
                )

            snapshots = database.list_project_snapshots("task-a")
            self.assertEqual(1, len(snapshots))
            self.assertEqual("latest", snapshots[0]["request_summary"])


if __name__ == "__main__":
    unittest.main()
