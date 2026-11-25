import importlib.util
import os
import pathlib
import unittest
from unittest.mock import patch

os.environ['GROUP_WORKER_TOKEN'] = 'unit-test-secret-local'
os.environ['GROUP_MEDIA_ALLOWED_HOSTS'] = 'res.cloudinary.com'
spec = importlib.util.spec_from_file_location('group_bridge_v12',
                                              pathlib.Path(__file__).parents[1] / 'bridge' / 'bridge.py')
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)

P = dict(id=b.TASK_ID, groupUrl=f'https://www.facebook.com/groups/{b.GROUP_ID}/',
         content='A cafe test Post', taskStatus='READY', postStatus='PUBLISHED',
         approvalStatus='APPROVED', due=True, imageCount=0, unsupportedMediaCount=0)
VURL = 'https://res.cloudinary.com/demo/video/upload/test.mp4'
IURL = 'https://res.cloudinary.com/demo/image/upload/test.png'


def manifest(rows):
    return {'id': b.TASK_ID, 'groupId': b.GROUP_ID, 'media': rows}


def read_fixture(kind, post=P):
    def read(path, method='GET', body=None):
        if path == '/preview': return dict(post)
        if path == '/image-manifest':
            return manifest([{'type': 'VIDEO', 'url': VURL}] if kind == 'VIDEO'
                            else [{'type': 'IMAGE', 'url': IURL}] if kind == 'IMAGE' else [])
        if path == '/video-claim':
            return dict(id=b.TASK_ID, groupUrl=post['groupUrl'], content=post['content'],
                        imageUrls=[], videoUrls=[VURL], unsupportedMediaCount=0)
        if path == '/image-claim':
            return dict(id=b.TASK_ID, groupUrl=post['groupUrl'], content=post['content'],
                        imageUrls=[IURL], unsupportedMediaCount=0)
        if path == '/claim':
            return dict(id=b.TASK_ID, groupUrl=post['groupUrl'], content=post['content'],
                        imageUrls=[], unsupportedMediaCount=0)
        return None

    return read


class FakeResponse:
    def __init__(self, payload):
        self.payload = payload
        self.index = 0
        self.headers = {'Content-Length': str(len(payload))}

    def __enter__(self): return self

    def __exit__(self, *args): pass

    def read(self, n):
        data = self.payload[self.index:self.index + n];
        self.index += len(data);
        return data


class FakeOpener:
    def __init__(self, payload): self.payload = payload

    def open(self, req, timeout=45): return FakeResponse(self.payload)


class Tests(unittest.TestCase):
    def setUp(self):
        b.STATE.update(preview=None, manifest=None, digest=None, claimed=False, reported=False,
                       staged=None, mediaKind=None)
        for path in b.STAGING_DIR.iterdir():
            if path.is_file(): path.unlink()

    def test_image_classification(self):
        post = {**P, 'imageCount': 1}
        with patch.object(b, 'upstream', side_effect=read_fixture('IMAGE', post)):
            result = b.get_preview()
        self.assertEqual((result['mediaKind'], result['mediaCount'], result['imageCount']), ('IMAGE', 1, 1))

    def test_text_classification(self):
        with patch.object(b, 'upstream', side_effect=read_fixture('TEXT')):
            result = b.get_preview()
            task = b.claim_task({'digest': result['digest']})
        self.assertEqual((task['mediaKind'], task['mediaCount']), ('TEXT', 0))
        self.assertTrue(b.STATE['claimed'])

    def test_video_classification_and_claim_needs_staging(self):
        post = {**P, 'unsupportedMediaCount': 1}
        with patch.object(b, 'upstream', side_effect=read_fixture('VIDEO', post)):
            result = b.get_preview()
            self.assertEqual((result['mediaKind'], result['mediaCount']), ('VIDEO', 1))
            with self.assertRaisesRegex(b.BridgeError, 'staged'):
                b.claim_task({'digest': result['digest']})
            self.assertFalse(b.STATE['claimed'])

    def test_video_mp4_stage_claim(self):
        post = {**P, 'unsupportedMediaCount': 1}
        mp4 = b'\x00\x00\x00\x20ftypisom' + b'0' * 3000
        with patch.object(b, 'upstream', side_effect=read_fixture('VIDEO', post)), \
                patch.object(b.urllib.request, 'build_opener', return_value=FakeOpener(mp4)):
            result = b.get_preview()
            staged = b.stage_video({'digest': result['digest']})
            self.assertEqual(staged['count'], 1)
            self.assertEqual(pathlib.Path(staged['files'][0]['path']).read_bytes(), mp4)
            claimed = b.claim_task({'digest': result['digest']})
            self.assertEqual(claimed['mediaKind'], 'VIDEO')
            self.assertTrue(b.STATE['claimed'])
            with self.assertRaises(b.BridgeError):
                b.claim_task({'digest': result['digest']})

    def test_video_published_other_platform_is_allowed(self):
        post = {**P, 'unsupportedMediaCount': 1, 'postStatus': 'PUBLISHED'}
        with patch.object(b, 'upstream', side_effect=read_fixture('VIDEO', post)):
            result = b.get_preview()
        self.assertEqual(result['mediaKind'], 'VIDEO')

    def test_video_readonly_pending(self):
        post = {**P, 'unsupportedMediaCount': 1, 'taskStatus': 'PENDING', 'due': False}
        called = []
        reader = read_fixture('VIDEO', post)

        def fake(path, method='GET', body=None):
            called.append(method);
            return reader(path, method, body)

        with patch.object(b, 'upstream', side_effect=fake):
            report = b.inspect_media()
        self.assertEqual((report['mediaKind'], report['taskStatus']), ('VIDEO', 'PENDING'))
        self.assertFalse(b.STATE['claimed'])
        self.assertTrue(all(v == 'GET' for v in called))

    def test_invalid_mixed_media(self):
        post = {**P, 'imageCount': 1, 'unsupportedMediaCount': 1}
        rows = [{'type': 'IMAGE', 'url': IURL}, {'type': 'VIDEO', 'url': VURL}]
        with patch.object(b, 'upstream', return_value=manifest(rows)):
            with self.assertRaisesRegex(b.BridgeError, 'Mixed'):
                b.media_manifest(post)

    def test_invalid_video_type_and_size(self):
        for typ in (b'<html></html>', b'RIFF', b'\x00\x00\x00\x20ftypqt  '):
            with self.assertRaises(b.BridgeError): b.video_type(typ)
        self.assertEqual(b.video_type(b'\x00\x00\x00\x20ftypisom'), '.mp4')

    def test_refuse_unknown_cdn(self):
        for url in ['https://localhost/test.mp4', 'https://127.0.0.1/video.mp4',
                    'http://res.cloudinary.com/demo.mp4', 'https://evil.site/a.mp4',
                    'https://res.cloudinary.com:444/video.mp4']:
            with self.subTest(url=url):
                with self.assertRaises(b.BridgeError): b.valid_media_url(url)

    def test_different_media_url_after_preview_blocked(self):
        post = {**P, 'unsupportedMediaCount': 1}
        with patch.object(b, 'upstream', side_effect=read_fixture('VIDEO', post)):
            result = b.get_preview()

        def mutated(path, method='GET', body=None):
            value = read_fixture('VIDEO', post)(path, method, body)
            if path == '/image-manifest': value['media'][0]['url'] = VURL + '?mutated=true'
            return value

        with patch.object(b, 'upstream', side_effect=mutated):
            with self.assertRaisesRegex(b.BridgeError, 'changed'):
                b.stage_video({'digest': result['digest']})
        self.assertFalse(b.STATE['claimed'])

    def test_final_result_strict_permalink(self):
        b.STATE['claimed'] = True
        with self.assertRaises(b.BridgeError):
            b.report_result({'status': 'PUBLISHED', 'publishedUrl': 'https://facebook.com/outside'})
        with patch.object(b, 'upstream', return_value=None):
            ret = b.report_result({'status': 'PUBLISHED', 'publishedUrl':
                f'https://www.facebook.com/groups/{b.GROUP_ID}/posts/123'})
        self.assertEqual(ret['status'], 'PUBLISHED')


if __name__ == '__main__': unittest.main()
