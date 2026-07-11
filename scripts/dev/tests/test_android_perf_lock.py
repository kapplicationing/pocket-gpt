import json
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from android_perf_lock import (  # noqa: E402
    LockError,
    acquire_lock,
    release_lock,
    validate_lock,
)


class AndroidPerfLockTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def acquire(self, *, owner_pid: int = 123):
        return acquire_lock(
            serial="device-123",
            package="com.pocketagent.android",
            owner_pid=owner_pid,
            owner_command="perf-interaction-gate settings-nav",
            root=self.root,
        )

    def test_lock_is_atomic_and_reports_current_owner(self):
        lease = self.acquire(owner_pid=123)

        with self.assertRaisesRegex(LockError, "owner_pid=123"):
            self.acquire(owner_pid=456)

        metadata = json.loads((lease.path / "owner.json").read_text(encoding="utf-8"))
        self.assertEqual(metadata["serial"], "device-123")
        self.assertEqual(metadata["package"], "com.pocketagent.android")
        self.assertEqual(metadata["owner_pid"], 123)
        self.assertEqual(metadata["owner_command"], "perf-interaction-gate settings-nav")

    def test_child_must_present_exact_owner_token(self):
        lease = self.acquire()

        validate_lock(
            serial="device-123",
            package="com.pocketagent.android",
            token=lease.token,
            root=self.root,
        )
        with self.assertRaisesRegex(LockError, "token"):
            validate_lock(
                serial="device-123",
                package="com.pocketagent.android",
                token="wrong-token",
                root=self.root,
            )

    def test_wrong_token_cannot_release_and_owner_cleanup_allows_reacquire(self):
        lease = self.acquire()

        with self.assertRaisesRegex(LockError, "token"):
            release_lock(
                serial="device-123",
                package="com.pocketagent.android",
                token="wrong-token",
                root=self.root,
            )
        self.assertTrue(lease.path.is_dir())

        release_lock(
            serial="device-123",
            package="com.pocketagent.android",
            token=lease.token,
            root=self.root,
        )
        replacement = self.acquire(owner_pid=456)
        self.assertTrue(replacement.path.is_dir())


if __name__ == "__main__":
    unittest.main()
