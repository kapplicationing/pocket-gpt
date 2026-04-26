from __future__ import annotations

import json
import unittest

from tools.devctl import doctor


class _Result:
    def __init__(self, returncode: int, stdout: str = "", stderr: str = ""):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


class DoctorTest(unittest.TestCase):
    def test_doctor_json_output_shape(self) -> None:
        report = doctor.DoctorReport(checks=[])
        payload = json.loads(report.to_json())
        self.assertIn("ok", payload)
        self.assertIn("checks", payload)
        self.assertIn("failed_required", payload)

    def test_doctor_marks_missing_sdk(self) -> None:
        original_which = doctor.shutil.which
        original_run = doctor.run_subprocess
        try:
            doctor.shutil.which = lambda name: "/usr/bin/adb" if name == "adb" else None

            def fake_run(command, **_kwargs):
                if command[:3] == ["adb", "devices", "-l"]:
                    return _Result(0, "List of devices attached\nDEVICE_SERIAL_REDACTED device\n", "")
                if command == ["adb", "mdns", "services"]:
                    return _Result(0, "", "")
                if command[:4] == ["adb", "-s", "DEVICE_SERIAL_REDACTED", "get-state"]:
                    return _Result(0, "device\n", "")
                if command[:4] == ["adb", "-s", "DEVICE_SERIAL_REDACTED", "shell"]:
                    return _Result(0, "doctor-ok\n", "")
                return _Result(0, "", "")

            doctor.run_subprocess = fake_run
            report = doctor.run_doctor(env={})
            sdk = [check for check in report.checks if check.name == "android_sdk"][0]
            self.assertFalse(sdk.ok)
        finally:
            doctor.shutil.which = original_which
            doctor.run_subprocess = original_run

    def test_doctor_detects_no_device(self) -> None:
        original_which = doctor.shutil.which
        original_run = doctor.run_subprocess
        try:
            doctor.shutil.which = lambda name: "/usr/bin/adb" if name == "adb" else None

            def fake_run(command, **_kwargs):
                if command[:3] == ["adb", "devices", "-l"]:
                    return _Result(0, "List of devices attached\n\n", "")
                return _Result(0, "", "")

            doctor.run_subprocess = fake_run
            report = doctor.run_doctor(env={"ANDROID_HOME": "/tmp"})
            device = [check for check in report.checks if check.name == "adb_device"][0]
            self.assertFalse(device.ok)
        finally:
            doctor.shutil.which = original_which
            doctor.run_subprocess = original_run

    def test_doctor_detects_missing_maestro(self) -> None:
        original_which = doctor.shutil.which
        original_run = doctor.run_subprocess
        try:
            def fake_which(name: str):
                if name == "adb":
                    return "/usr/bin/adb"
                return None

            doctor.shutil.which = fake_which

            def fake_run(command, **_kwargs):
                if command[:3] == ["adb", "devices", "-l"]:
                    return _Result(0, "List of devices attached\nDEVICE_SERIAL_REDACTED device\n", "")
                if command == ["adb", "mdns", "services"]:
                    return _Result(0, "", "")
                if command[:4] == ["adb", "-s", "DEVICE_SERIAL_REDACTED", "get-state"]:
                    return _Result(0, "device\n", "")
                if command[:4] == ["adb", "-s", "DEVICE_SERIAL_REDACTED", "shell"]:
                    return _Result(0, "doctor-ok\n", "")
                if command[0] == "./gradlew":
                    return _Result(1, "", "install failed")
                return _Result(0, "", "")

            doctor.run_subprocess = fake_run
            report = doctor.run_doctor(env={"ANDROID_HOME": "/tmp"})
            maestro = [check for check in report.checks if check.name == "maestro_cli"][0]
            self.assertFalse(maestro.ok)
        finally:
            doctor.shutil.which = original_which
            doctor.run_subprocess = original_run

    def test_doctor_accepts_explicit_serial_when_multiple_devices_are_connected(self) -> None:
        original_which = doctor.shutil.which
        original_run = doctor.run_subprocess
        try:
            doctor.shutil.which = lambda name: "/usr/bin/adb" if name == "adb" else None

            def fake_run(command, **_kwargs):
                if command[:3] == ["adb", "devices", "-l"]:
                    return _Result(
                        0,
                        "List of devices attached\nSER123 device product:a model:x\nSER456 device product:b model:y\n",
                        "",
                    )
                if command == ["adb", "mdns", "services"]:
                    return _Result(0, "", "")
                if command[:4] == ["adb", "-s", "SER456", "get-state"]:
                    return _Result(0, "device\n", "")
                if command[0] == "./gradlew":
                    self.assertIn("-Pandroid.injected.device.serial=SER456", command)
                    return _Result(0, "", "")
                if command[:4] == ["adb", "-s", "SER456", "shell"]:
                    return _Result(0, "Status: ok\n", "")
                return _Result(0, "", "")

            doctor.run_subprocess = fake_run
            report = doctor.run_doctor(env={"ANDROID_HOME": "/tmp", "ADB_SERIAL": "SER456"})
            device = [check for check in report.checks if check.name == "adb_device"][0]
            install = [check for check in report.checks if check.name == "app_installability"][0]
            launch = [check for check in report.checks if check.name == "app_launchability"][0]
            self.assertTrue(device.ok)
            self.assertIn("SER456", device.detail)
            self.assertTrue(install.ok)
            self.assertTrue(launch.ok)
        finally:
            doctor.shutil.which = original_which
            doctor.run_subprocess = original_run

    def test_doctor_rejects_unknown_explicit_serial(self) -> None:
        original_which = doctor.shutil.which
        original_run = doctor.run_subprocess
        try:
            doctor.shutil.which = lambda name: "/usr/bin/adb" if name == "adb" else None

            def fake_run(command, **_kwargs):
                if command[:3] == ["adb", "devices", "-l"]:
                    return _Result(
                        0,
                        "List of devices attached\nSER123 device product:a model:x\nSER456 device product:b model:y\n",
                        "",
                    )
                if command == ["adb", "mdns", "services"]:
                    return _Result(0, "", "")
                return _Result(0, "", "")

            doctor.run_subprocess = fake_run
            report = doctor.run_doctor(env={"ANDROID_HOME": "/tmp", "ADB_SERIAL": "SER999"})
            device = [check for check in report.checks if check.name == "adb_device"][0]
            self.assertFalse(device.ok)
            self.assertIn("SER999", device.detail)
        finally:
            doctor.shutil.which = original_which
            doctor.run_subprocess = original_run

    def test_doctor_resolves_endpoint_alias_to_mdns_transport(self) -> None:
        original_which = doctor.shutil.which
        original_run = doctor.run_subprocess
        try:
            doctor.shutil.which = lambda name: "/usr/bin/adb" if name == "adb" else None

            def fake_run(command, **_kwargs):
                if command[:3] == ["adb", "devices", "-l"]:
                    return _Result(
                        0,
                        "List of devices attached\nadb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp device product:a model:x transport_id:7\n",
                        "",
                    )
                if command == ["adb", "mdns", "services"]:
                    return _Result(0, "adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp 192.168.1.37:37361\n", "")
                if command[:4] == ["adb", "-s", "adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp", "get-state"]:
                    return _Result(0, "device\n", "")
                if command[:4] == ["adb", "-s", "adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp", "shell"]:
                    return _Result(0, "doctor-ok\n", "")
                if command[0] == "./gradlew":
                    self.assertIn("-Pandroid.injected.device.serial=adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp", command)
                    return _Result(0, "", "")
                return _Result(0, "", "")

            doctor.run_subprocess = fake_run
            report = doctor.run_doctor(env={"ANDROID_HOME": "/tmp", "ADB_SERIAL": "192.168.1.37:37361"})
            device = [check for check in report.checks if check.name == "adb_device"][0]
            transport = [check for check in report.checks if check.name == "adb_transport"][0]
            self.assertTrue(device.ok)
            self.assertIn("192.168.1.37:37361", device.detail)
            self.assertIn("adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp", device.detail)
            self.assertTrue(transport.ok)
        finally:
            doctor.shutil.which = original_which
            doctor.run_subprocess = original_run


if __name__ == "__main__":
    unittest.main()
