#!/usr/bin/env python3
"""NovelLex companion. Runs ORIGINAL GLiNER2.5 Small, never the trained adapter.

Default loopback binding prevents other devices accessing user chapter text.
For phone-to-PC LAN use --host 0.0.0.0 --token LONG_RANDOM_SECRET and a trusted network.
"""
import argparse
import hmac
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock

MODEL_ID = 'fastino/gliner2.5-small-v1'
LABELS = [
    'fictional creature species', 'fictional person', 'named fantasy technique',
    'named fictional organization', 'named fictional place',
    'named fictional world concept', 'named magical artifact', 'named rank',
    'personal title',
]
MAX_REQUEST_BYTES = 128 * 1024
MAX_PASSAGES = 40
MAX_PASSAGE_CHARS = 1800

class Engine:
    def __init__(self):
        self.model = None
        self.lock = Lock()

    def extract(self, passages):
        with self.lock:
            if self.model is None:
                from gliner2 import AutoExtractor
                self.model = AutoExtractor.from_pretrained(MODEL_ID, map_location='cpu')
                self.model.eval()
            results = []
            for passage in passages:
                raw = self.model.extract_entities(
                    passage, LABELS, threshold=0.5, include_spans=True)
                entities = []
                for label, mentions in raw.get('entities', {}).items():
                    if label not in LABELS:
                        continue
                    for item in mentions or []:
                        try:
                            start, end = int(item['start']), int(item['end'])
                            surface = item['text']
                            if not (0 <= start < end <= len(passage)):
                                continue
                            if passage[start:end] != surface:
                                continue
                            entities.append({'label': label, 'text': surface,
                                             'start': start, 'end': end})
                        except (KeyError, ValueError, TypeError):
                            continue
                results.append({'entities': entities})
            return results


def create_handler(engine, secret):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            # Never log book text, URL query, or request bodies.
            pass

        def respond(self, code, data):
            body = json.dumps(data, ensure_ascii=False).encode('utf-8')
            self.send_response(code)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def authorized(self):
            auth = self.headers.get('Authorization', '')
            token = auth[7:] if auth.startswith('Bearer ') else ''
            return hmac.compare_digest(token, secret) if secret else False

        def do_GET(self):
            if self.path != '/v1/health':
                return self.respond(404, {'error': 'not_found'})
            if not self.authorized():
                return self.respond(401, {'error': 'unauthorized'})
            self.respond(200, {'status': 'ready', 'model': MODEL_ID,
                               'loaded': engine.model is not None})

        def do_POST(self):
            if self.path != '/v1/extract':
                return self.respond(404, {'error': 'not_found'})
            if not self.authorized():
                return self.respond(401, {'error': 'unauthorized'})
            try:
                size = int(self.headers.get('Content-Length', '0'))
                if size <= 0 or size > MAX_REQUEST_BYTES:
                    return self.respond(413, {'error': 'request_size'})
                payload = json.loads(self.rfile.read(size))
                passages = payload.get('passages')
                if (not isinstance(passages, list) or not 1 <= len(passages) <= MAX_PASSAGES
                        or any(not isinstance(p, str) or not 1 <= len(p) <= MAX_PASSAGE_CHARS
                               for p in passages)):
                    return self.respond(400, {'error': 'invalid_passages'})
                self.respond(200, {'model': MODEL_ID, 'results': engine.extract(passages)})
            except (ValueError, TypeError, json.JSONDecodeError):
                self.respond(400, {'error': 'bad_request'})
            except Exception:
                self.respond(503, {'error': 'model_unavailable'})
    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--token', default=os.environ.get('NOVELLEX_TOKEN', ''))
    args = parser.parse_args()
    if len(args.token) < 24:
        parser.error('NOVELLEX_TOKEN must contain at least 24 characters')
    if args.host not in ('127.0.0.1', '::1', 'localhost'):
        print('WARNING: LAN mode transmits chapters over HTTP; use a trusted private network.')
    server = ThreadingHTTPServer((args.host, args.port), create_handler(Engine(), args.token))
    print('NovelLex original GLiNER listening on', args.host, args.port, flush=True)
    server.serve_forever()

if __name__ == '__main__':
    main()
