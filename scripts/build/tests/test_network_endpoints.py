import unittest
from unittest.mock import patch

from scripts.build.lib import doctor


class NetworkEndpointsTest(unittest.TestCase):
    def test_build_does_not_probe_slingbreak(self):
        # Even obsolete local properties must not reintroduce the removed build input.
        properties = {"slingBreak.sourceUrl": "https://example.com/slingbreak.tar.gz"}
        with patch.object(doctor, "_parse_gradle_properties", return_value=properties), \
                patch.object(doctor, "_missing_build_deps", return_value=[]):
            endpoints = doctor._network_endpoints()
        self.assertEqual(6, len(endpoints))
        self.assertFalse(any("sling" in endpoint.label.lower() or "sling" in endpoint.url.lower()
                             for endpoint in endpoints))


if __name__ == "__main__":
    unittest.main()
