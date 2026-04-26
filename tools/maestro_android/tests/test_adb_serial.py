from __future__ import annotations

import unittest

from tools.maestro_android.adb_serial import (
    is_mdns_tls_serial,
    is_network_serial,
    merge_mdns_aliases,
    parse_adb_devices_output,
    parse_adb_mdns_services_output,
    resolve_requested_serial,
)


class AdbSerialTest(unittest.TestCase):
    def test_parse_and_resolve_mdns_endpoint_alias(self) -> None:
        devices = parse_adb_devices_output(
            "List of devices attached\n"
            "adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp device product:a model:x transport_id:7\n"
        )
        mdns = parse_adb_mdns_services_output(
            "adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp 192.168.1.37:37361\n"
        )

        merged = merge_mdns_aliases(devices, mdns)
        matched = resolve_requested_serial("192.168.1.37:37361", merged)

        self.assertIsNotNone(matched)
        self.assertEqual("adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp", matched.serial)

    def test_parse_mdns_services_supports_split_service_and_type_columns(self) -> None:
        mdns = parse_adb_mdns_services_output(
            "List of discovered mdns services\n"
            "adb-RFCT2178PDV-nZWer7\t_adb-tls-connect._tcp\t192.168.1.37:37361\n"
        )

        self.assertEqual(1, len(mdns))
        self.assertEqual("adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp", mdns[0].service)
        self.assertEqual("192.168.1.37:37361", mdns[0].endpoint)

    def test_network_serial_detection_covers_mdns_and_ip_port(self) -> None:
        self.assertTrue(is_mdns_tls_serial("adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp"))
        self.assertTrue(is_network_serial("adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp"))
        self.assertTrue(is_network_serial("192.168.1.37:37361"))
        self.assertFalse(is_network_serial("SER123"))


if __name__ == "__main__":
    unittest.main()
