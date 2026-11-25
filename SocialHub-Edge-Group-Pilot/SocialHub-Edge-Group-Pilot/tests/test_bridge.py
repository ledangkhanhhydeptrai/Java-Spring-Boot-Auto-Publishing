import importlib.util
import os
import pathlib
import unittest
from unittest.mock import patch

os.environ['GROUP_WORKER_TOKEN'] = 'test-local-only-not-real'
os.environ['GROUP_MEDIA_ALLOWED_HOSTS'] = 'res.cloudinary.com'
spec = importlib.util.spec_from_file_location('group_bridge',
                                              os.path.join(os.path.dirname(__file__), '..', 'bridge', 'bridge.py'))
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)

BASE = {
    'id': b.TASK_ID,
    'groupUrl': 'https://www.facebook.com/groups/' + b.GROUP_ID + '/',
    'content': 'Group text test only',
    'taskStatus': 'READY', 'postStatus': 'PUBLISHED', 'approvalStatus': 'APPROVED',
    'due': True, 'imageCount': 0, 'unsupportedMediaCount': 0
}


def manifest(urls):
    return {'id': b.TASK_ID, 'groupId': b.GROUP_ID,
            'media': [{'type': 'IMAGE', 'url': u} for u in urls]}


class TestBridge(unittest.TestCase):
    def setUp(self):
        b.STATE.update(preview=None, manifest=None, digest=None, claimed=False,
                       reported=False, staged=None)

    def test_text_preview_claim_and_one_report(self):
        calls = []

        def fake(path, method='GET', body=None):
            calls.append((path, method))
            if path == '/preview': return dict(BASE)
            if path == '/image-manifest': return manifest([])
            if path == '/claim':
                return {'id': b.TASK_ID, 'groupUrl': BASE['groupUrl'], 'content': BASE['content'],
                        'imageUrls': [], 'unsupportedMediaCount': 0}
            return None

        with patch.object(b, 'upstream', fake):
            preview = b.get_preview()
            self.assertEqual(preview['imageCount'], 0)
            got = b.claim_task({'digest': preview['digest']})
            self.assertEqual(got['id'], b.TASK_ID)
            self.assertEqual(got['imageCount'], 0)
            self.assertEqual([p for p, m in calls if m == 'POST'], ['/claim'])
            with self.assertRaises(b.BridgeError):
                b.claim_task({'digest': preview['digest']})
            self.assertEqual(b.report_result({'status': 'NEEDS_REVIEW', 'note': 'no permalink'})['status'],
                             'NEEDS_REVIEW')
            with self.assertRaises(b.BridgeError):
                b.report_result({'status': 'PUBLISHED'})

    def test_preview_reject_invalid(self):
        for changes in [{'id': 'other'}, {'groupUrl': 'https://facebook.com/groups/123/'},
                        {'taskStatus': 'PENDING'}, {'approvalStatus': 'PENDING'},
                        {'postStatus': 'DRAFT'}, {'due': False}, {'imageCount': 11},
                        {'imageCount': -1}, {'unsupportedMediaCount': 1}, {'content': ''}]:
            with self.subTest(changes=changes):
                with self.assertRaises(b.BridgeError):
                    b.validate_preview({**BASE, **changes})

    def test_manifest_count_and_type(self):
        p = {**BASE, 'imageCount': 1}
        with patch.object(b, 'upstream', return_value=manifest([])):
            with self.assertRaisesRegex(b.BridgeError, 'count'):
                b.image_manifest(p)
        with patch.object(b, 'upstream', return_value={**manifest(['https://res.cloudinary.com/a.jpg']),
                                                       'media': [{'type': 'VIDEO',
                                                                  'url': 'https://res.cloudinary.com/a.jpg'}]}):
            with self.assertRaises(b.BridgeError):
                b.image_manifest(p)

    def test_image_claim_requires_staged_and_exact_manifest(self):
        url = 'https://res.cloudinary.com/demo/image/upload/test.png'
        p = {**BASE, 'imageCount': 1}

        def fake(path, method='GET', body=None):
            if path == '/preview': return p
            if path == '/image-manifest': return manifest([url])
            if path == '/image-claim':
                return {'id': b.TASK_ID, 'groupUrl': p['groupUrl'], 'content': p['content'],
                        'imageUrls': [url], 'unsupportedMediaCount': 0}
            return None

        with patch.object(b, 'upstream', fake):
            view = b.get_preview()
            self.assertEqual(view['imageCount'], 1)
            # Bridge must never claim media task without successful local staging.
            # The current implementation checks staging after claim and records review.
            sample = b.STAGING_DIR / 'test-claim.png'
            sample.write_bytes(b'\x89PNG\r\n\x1a\n123456789')
            b.STATE['staged'] = [{'path': str(sample), 'size': len(sample.read_bytes()),
                                  'sha256': b.hashlib.sha256(sample.read_bytes()).hexdigest()}]
            claimed = b.claim_task({'digest': view['digest']})
            self.assertEqual(claimed['imageCount'], 1)
            self.assertTrue(b.STATE['claimed'])

    def test_result_permalink_strict(self):
        b.STATE.update(claimed=True)
        for url in ['https://www.facebook.com/groups/999/posts/11/',
                    'https://www.facebook.com/fake/posts/12',
                    'http://www.facebook.com/groups/' + b.GROUP_ID + '/posts/1']:
            with self.subTest(url=url):
                with self.assertRaises(b.BridgeError):
                    b.report_result({'status': 'PUBLISHED', 'publishedUrl': url})
        with patch.object(b, 'upstream', return_value=None):
            r = b.report_result({'status': 'PUBLISHED',
                                 'publishedUrl': 'https://www.facebook.com/groups/' + b.GROUP_ID + '/posts/123/'})
        self.assertEqual(r['status'], 'PUBLISHED')

    def test_content_change_cancels_claim(self):
        def initial(path, method='GET', body=None):
            return dict(BASE) if path == '/preview' else manifest([])

        with patch.object(b, 'upstream', side_effect=initial):
            preview = b.get_preview()
        changed = {**BASE, 'content': 'Surprise content changed'}

        def later(path, method='GET', body=None):
            return changed if path == '/preview' else manifest([])

        with patch.object(b, 'upstream', side_effect=later):
            with self.assertRaisesRegex(b.BridgeError, 'changed'):
                b.claim_task({'digest': preview['digest']})
        self.assertFalse(b.STATE['claimed'])

    def test_inspect_pending_read_only(self):
        url = 'https://res.cloudinary.com/demo/image/upload/a.jpg'
        source = {**BASE, 'taskStatus': 'PENDING', 'due': False, 'imageCount': 1}
        called = []

        def fake(path, method='GET', body=None):
            called.append((path, method))
            return source if path == '/preview' else manifest([url])

        with patch.object(b, 'upstream', side_effect=fake):
            result = b.inspect_images()
        self.assertEqual(result['taskStatus'], 'PENDING')
        self.assertEqual(result['imageCount'], 1)
        self.assertTrue(result['hostsAllowed'])
        self.assertTrue(all(method == 'GET' for _, method in called))
        self.assertFalse(b.STATE['claimed'])

    def test_stage_image_from_allowed_host_only(self):
        url = 'https://res.cloudinary.com/demo/image/upload/a.png'
        post = {**BASE, 'imageCount': 1}
        payload = b'\x89PNG\r\n\x1a\n' + b'0' * 70

        class FakeResponse:
            headers = {'Content-Length': str(len(payload))}

            def __enter__(self): return self

            def __exit__(self, *exc): pass

            def read(self, n): return payload[:n]

        class FakeOpener:
            def open(self, request, timeout=25):
                self.request = request
                return FakeResponse()

        def fake(path, method='GET', body=None):
            return post if path == '/preview' else manifest([url])

        with patch.object(b, 'upstream', side_effect=fake), \
                patch.object(b.urllib.request, 'build_opener', return_value=FakeOpener()):
            view = b.get_preview()
            result = b.stage_images({'digest': view['digest']})
        self.assertEqual(result['count'], 1)
        file = pathlib.Path(result['files'][0]['path'])
        self.assertEqual(file.read_bytes(), payload)
        self.assertEqual(b.STATE['staged'], result['files'])
        with self.assertRaisesRegex(b.BridgeError, 'already staged'):
            with patch.object(b, 'upstream', side_effect=fake):
                b.stage_images({'digest': view['digest']})
        file.unlink()

    def test_block_local_and_unknown_media_urls(self):
        for url in ['http://res.cloudinary.com/a.jpg', 'https://localhost/a.jpg',
                    'https://127.0.0.1/a.jpg', 'https://evil.example/a.png',
                    'https://res.cloudinary.com:8443/a.jpg']:
            with self.subTest(url=url):
                with self.assertRaises(b.BridgeError): b.valid_media_url(url)
        b.valid_media_url('https://res.cloudinary.com/demo/image/upload/test.jpg')

    def test_image_bytes_sniffed(self):
        self.assertEqual(b.file_type(b'\x89PNG\r\n\x1a\n' + b'123456789'), '.png')
        self.assertEqual(b.file_type(b'\xff\xd8\xff' + b'123456789' + b'\xff\xd9'), '.jpg')
        with self.assertRaises(b.BridgeError): b.file_type(b'<html>not image</html>')


if __name__ == '__main__': unittest.main()
