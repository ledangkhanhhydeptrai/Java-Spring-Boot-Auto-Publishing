import importlib.util
import os
import unittest
from unittest.mock import patch

os.environ['GROUP_WORKER_TOKEN'] = 'test-local-only-not-real'
spec = importlib.util.spec_from_file_location('group_bridge', os.path.join(os.path.dirname(__file__), '..', 'bridge', 'bridge.py'))
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)

BASE = {
    'id': b.TASK_ID,
    'groupUrl': 'https://www.facebook.com/groups/' + b.GROUP_ID + '/',
    'content': 'Group text test only',
    'taskStatus': 'READY', 'postStatus': 'READY', 'approvalStatus': 'APPROVED',
    'due': True, 'imageCount': 0, 'unsupportedMediaCount': 0
}

class TestBridge(unittest.TestCase):
    def setUp(self):
        b.STATE.update(preview=None, digest=None, claimed=False, reported=False)

    def test_readonly_preview_and_one_claim(self):
        calls=[]
        def fake(path, method='GET', body=None):
            calls.append((path, method))
            if path == '/preview': return dict(BASE)
            if path == '/claim':
                return {'id': b.TASK_ID, 'groupUrl':BASE['groupUrl'], 'content': BASE['content'],
                        'imageUrls': [], 'unsupportedMediaCount':0}
            return None
        with patch.object(b, 'upstream', fake):
            p = b.get_preview()
            self.assertEqual(calls, [('/preview','GET')])
            got = b.claim_task({'digest':p['digest']})
            self.assertEqual(got['id'], b.TASK_ID)
            self.assertEqual(calls, [('/preview','GET'),('/preview','GET'),('/claim','POST')])
            with self.assertRaises(b.BridgeError):
                b.claim_task({'digest':p['digest']})
            result = b.report_result({'status':'NEEDS_REVIEW','note':'Cannot verify post'})
            self.assertEqual(result['status'], 'NEEDS_REVIEW')
            with self.assertRaises(b.BridgeError): b.report_result({'status':'PUBLISHED'})

    def test_all_reject_invalid(self):
        for changes in [{'id': 'other'}, {'groupUrl':'https://facebook.com/groups/123/'},
                        {'taskStatus':'PENDING'}, {'approvalStatus':'PENDING'},
                        {'postStatus':'DRAFT'}, {'due':False}, {'imageCount':1},
                        {'unsupportedMediaCount':1}, {'content':''}]:
            with self.subTest(changes=changes):
                p={**BASE,**changes}
                with self.assertRaises(b.BridgeError): b.validate_preview(p)

    def test_result_permalink_strict(self):
        b.STATE.update(claimed=True)
        for url in ['https://www.facebook.com/groups/999/posts/11/',
                    'https://www.facebook.com/fake/posts/12', 'http://www.facebook.com/groups/'+b.GROUP_ID+'/posts/1']:
            with self.subTest(url=url):
                with self.assertRaises(b.BridgeError):
                    b.report_result({'status':'PUBLISHED','publishedUrl':url})
        with patch.object(b,'upstream',return_value=None):
            r=b.report_result({'status':'PUBLISHED','publishedUrl':'https://www.facebook.com/groups/'+b.GROUP_ID+'/posts/123/'})
        self.assertEqual(r['status'],'PUBLISHED')

    def test_content_change_cancels_claim(self):
        with patch.object(b,'upstream',return_value=BASE): p=b.get_preview()
        changed={**BASE, 'content': 'Surprise content changed'}
        with patch.object(b,'upstream',return_value=changed):
            with self.assertRaisesRegex(b.BridgeError,'changed'):
                b.claim_task({'digest':p['digest']})
        self.assertFalse(b.STATE['claimed'])

if __name__ == '__main__': unittest.main()
