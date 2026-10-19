"""Safety regressions for the local signing tool; no real credentials required."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('release', Path(__file__).with_name('release.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseSafetyTest(unittest.TestCase):
    def test_secret_in_repository_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'outside'):
            release.require_external(release.ROOT / '.tools' / 'private.json')

    def test_resolved_secret_in_repository_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'outside'):
            release.require_external(release.ROOT / 'app' / '..' / 'private.json')

    def test_external_secret_is_allowed(self):
        self.assertEqual(release.require_external(release.ROOT.parent / 'private.json'), release.ROOT.parent / 'private.json')

    def test_existing_output_is_never_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'release.apk'
            output.write_bytes(b'keep')
            with self.assertRaisesRegex(ValueError, 'exists'):
                release.require_new_output(output)
            self.assertEqual(output.read_bytes(), b'keep')

    def test_release_metadata_is_accepted(self):
        release.validate_badging(self.badging(), '1.1.4', 11)

    def test_windows_tool_line_endings_are_accepted(self):
        release.validate_badging(self.badging().replace('\n', '\r\n'), '1.1.4', 11)

    def test_debuggable_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'debuggable'):
            release.validate_badging(self.badging() + '\napplication-debuggable', '1.1.4', 11)

    def test_wrong_package_is_rejected(self):
        with self.assertRaises(ValueError):
            release.validate_badging(self.badging().replace('com.example.lifemanager', 'com.other'), '1.1.4', 11)

    def test_wrong_version_is_rejected(self):
        with self.assertRaises(ValueError):
            release.validate_badging(self.badging(), '1.1.5', 12)

    def test_min_sdk_change_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'SDK'):
            release.validate_badging(self.badging().replace("sdkVersion:'26'", "sdkVersion:'28'"), '1.1.4', 11)

    def test_failure_does_not_disclose_subprocess_output(self):
        with self.assertRaises(RuntimeError) as caught:
            release.run([__import__('sys').executable, '-c', "print('secret-from-tool');raise SystemExit(1)"])
        self.assertNotIn('secret-from-tool', str(caught.exception))

    def test_same_certificate_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'distinct'):
            release.validate_new_certificate(release.OLD_CERT)

    def test_invalid_fingerprint_is_rejected(self):
        with self.assertRaises(ValueError):
            release.validate_new_certificate('invalid')

    def test_regenerated_identity_is_rejected(self):
        with self.assertRaises(ValueError):
            release.validate_new_certificate('a' * 64)

    def test_label_cannot_impersonate_package_or_sdk(self):
        decoy = self.badging().replace("package:", "application-label:'package:")
        with self.assertRaises(ValueError):
            release.validate_badging(decoy, '1.1.4', 11)

    def test_conflicting_package_rows_are_rejected(self):
        with self.assertRaises(ValueError):
            release.validate_badging(self.badging() + "package: name='com.other' versionCode='11' versionName='1.1.4'\n", '1.1.4', 11)

    def test_conflicting_sdk_rows_are_rejected(self):
        with self.assertRaises(ValueError):
            release.validate_badging(self.badging() + "sdkVersion:'28'\n", '1.1.4', 11)

    @staticmethod
    def badging():
        return "package: name='com.example.lifemanager' versionCode='11' versionName='1.1.4'\nsdkVersion:'26'\ntargetSdkVersion:'35'\n"


if __name__ == '__main__':
    unittest.main()
