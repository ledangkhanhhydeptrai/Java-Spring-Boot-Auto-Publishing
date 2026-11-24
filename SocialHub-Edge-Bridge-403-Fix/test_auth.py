import importlib.util
import os
import unittest
from pathlib import Path

os.environ['GROUP_WORKER_TOKEN'] = 'test-only-not-real'
os.environ['GROUP_WORKER_EXPECTED_GROUP_ID'] = '1326570106277759'
os.environ['GROUP_WORKER_TARGET_TASK_ID'] = '15e8de3f-cd4b-4ffb-96d0-ae5ec42a6c49'
spec = importlib.util.spec_from_file_location('pilot_bridge', Path(__file__).parent / 'bridge' / 'bridge.py')
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

class AuthTests(unittest.TestCase):
    def auth(self, headers):
        obj = object.__new__(mod.Handler)
        obj.headers = headers
        return obj.authorized()

    def test_auth(self):
        good = mod.PAIR_CODE
        extension = 'chrome-extension://' + 'a'*32
        self.assertTrue(self.auth({'Origin': extension, 'X-SocialHub-Pair': good}))
        self.assertTrue(self.auth({'X-SocialHub-Pair': good}))
        self.assertFalse(self.auth({'Origin': extension, 'X-SocialHub-Pair': 'bad'}))
        self.assertFalse(self.auth({'Origin': 'https://facebook.com', 'X-SocialHub-Pair': good}))
        self.assertFalse(self.auth({'Origin': 'null', 'X-SocialHub-Pair': good}))
        self.assertFalse(self.auth({}))

if __name__ == '__main__':
    unittest.main()
