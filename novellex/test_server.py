import json
import unittest
from server import Engine, LABELS, create_handler
from http.server import ThreadingHTTPServer
from threading import Thread
from urllib.request import Request, urlopen
from urllib.error import HTTPError

class FakeModel:
    def extract_entities(self, text, labels, **kwargs):
        assert labels == LABELS
        return {'entities': {'fictional person': [
            {'text':'Arin', 'start':0, 'end':4},
            {'text':'wrong', 'start':0, 'end':5}]}}

class ServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        engine = Engine(); engine.model = FakeModel()
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), create_handler(engine, '0123456789abcdef0123456789abcdef'))
        cls.thread = Thread(target=cls.server.serve_forever, daemon=True); cls.thread.start()
        cls.url = 'http://127.0.0.1:' + str(cls.server.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close()

    def request(self, path, payload=None, token=True):
        data = None if payload is None else json.dumps(payload).encode()
        headers = {'Content-Type': 'application/json'}
        if token: headers['Authorization'] = 'Bearer 0123456789abcdef0123456789abcdef'
        req = Request(self.url + path, data=data, headers=headers)
        try:
            with urlopen(req, timeout=2) as r: return r.status, json.load(r)
        except HTTPError as e: return e.code, json.load(e)

    def test_auth(self):
        self.assertEqual(self.request('/v1/health', token=False)[0], 401)

    def test_health(self):
        self.assertEqual(self.request('/v1/health')[1]['status'], 'ready')

    def test_extract_offsets(self):
        status, data = self.request('/v1/extract', {'passages':['Arin entered.']})
        self.assertEqual(status, 200)
        self.assertEqual(data['results'][0]['entities'], [{'label':'fictional person','text':'Arin','start':0,'end':4}])

    def test_reject_invalid(self):
        self.assertEqual(self.request('/v1/extract', {'passages':['']})[0], 400)
        self.assertEqual(self.request('/v1/extract', {'passages':['a'*1801]})[0], 400)

if __name__ == '__main__': unittest.main()
