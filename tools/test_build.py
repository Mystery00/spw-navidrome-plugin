"""发布元数据边界回归，不依赖本机宿主。"""
import unittest
from build import validate_metadata, version_from_tag


class MetadataTest(unittest.TestCase):
    def test_release_tag(self):
        self.assertEqual(version_from_tag('v1.2.3'), '1.2.3')
        self.assertEqual(version_from_tag('v0.1.0'), '0.1.0')
        for tag in ('1.2.3', 'v01.2.3', 'v1.2', 'v1.2.3-rc1', 'v1.2.3+build',
                    'v1.2.3\n', 'v1.2.3/other', 'v１.2.3'):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                version_from_tag(tag)

    def setUp(self):
        self.meta = dict(pluginId='vip.mystery0.spw.navidrome', version='0.1.0',
                         mainClass='vip.mystery0.spw.navidrome.NavidromePlugin',
                         openSourceUrl='https://github.com/Mystery00/spw-navidrome-plugin',
                         permissions=['class-transform'])

    def test_valid_metadata(self):
        validate_metadata(self.meta)

    def test_rejects_missing_fields_and_manifest_injection(self):
        for key in ('pluginId', 'version', 'mainClass'):
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_metadata({**self.meta, key: ''})
        with self.assertRaises(ValueError):
            validate_metadata({**self.meta, 'name': 'Name\r\nPlugin-Id: other'})

    def test_rejects_unsupported_permissions_and_invalid_source_url(self):
        for changes in ({'permissions': ['unknown']}, {'permissions': ['class-transform'] * 2},
                        {'openSourceUrl': 'file:///private'}, {'version': 'latest'},
                        {'pluginId': '../escape'}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate_metadata({**self.meta, **changes})


if __name__ == '__main__':
    unittest.main()
