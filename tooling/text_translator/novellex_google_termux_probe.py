#!/usr/bin/env python3
"""NoveLA Google Free/PA request-shape probe. Python 3 stdlib only.
No APK, no changes to installed NoveLA, no credentials logged.
Source: NoveLA_float/default TranslationManagerGoogleFree/PA.kt @ a468928910b6be17.
"""
import argparse
import datetime as dt
import html
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

FREE_URL = "https://translate.googleapis.com/translate_a/single"
PA_URL = "https://translate-pa.googleapis.com/v1/translateHtml"
UA = os.environ.get("NOVELA_USER_AGENT", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
TEST_CASES = [
    ("Spirit Core", "Michael absorbed the Spirit Core and became stronger."),
    ("Golden Road", "The guards watched Michael enter the Golden Road at dawn."),
    ("Supreme Skill", "She used the Supreme Skill to protect her brother."),
    ("Mithril", "The blacksmith forged a ring from Mithril for his daughter."),
    ("Arion", "Arion gave his sword to his companion before the battle."),
]
VARIANTS = ("plain", "translate_no", "class_notranslate", "brackets", "placeholders")


def transform(sentence, term, variant):
    if variant == "plain":
        return sentence
    if variant == "translate_no":
        replacement = '<span translate="no">' + html.escape(term) + '</span>'
    elif variant == "class_notranslate":
        replacement = '<span class="notranslate">' + html.escape(term) + '</span>'
    elif variant == "brackets":
        replacement = '⟦' + term + '⟧'
    elif variant == "placeholders":
        replacement = 'ZXQ0001ZXQ'
    else:
        raise ValueError(variant)
    return re.sub(r'(?<!\w)' + re.escape(term) + r'(?!\w)', lambda _: replacement, sentence, count=1)


def call(url, *, method="GET", payload=None, headers=None, timeout=20):
    request = urllib.request.Request(url, data=payload, method=method, headers={"User-Agent": UA, **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"HTTP {e.code} for {urllib.parse.urlsplit(url).hostname}") from None


def google_free(text, source, target):
    """Mirrors NoveLA GoogleFree: <=500 chars GET, >500 POST form, client=gtx."""
    fields = {"client": "gtx", "sl": source, "tl": target, "dt": "t", "q": text}
    encoded = urllib.parse.urlencode(fields).encode("utf-8")
    if len(text) > 500:
        response = call(FREE_URL, method="POST", payload=encoded,
                        headers={"Content-Type": "application/x-www-form-urlencoded"})
    else:
        response = call(FREE_URL + "?" + encoded.decode("ascii"))
    data = json.loads(response)
    return "".join(str(item[0]) for item in data[0] if item and item[0] is not None).strip()


def google_pa(text, source, target, key):
    """Mirrors NoveLA GooglePA translateHtml JSON structure; wraps nothing extra."""
    if not key:
        raise RuntimeError("PA anahtarı yok; --pa-key-env veya --discover-pa-key kullan")
    body = json.dumps([[text, source, target], "wt_lib"], ensure_ascii=False).encode("utf-8")
    response = call(PA_URL, method="POST", payload=body,
                    headers={"Content-Type": "application/json+protobuf",
                             "X-Goog-API-Key": key, "Origin": "https://translate.google.com"})
    translated = json.loads(response)[0][0]
    if not isinstance(translated, str):
        raise ValueError("Beklenmeyen PA çeviri cevabı")
    # NoveLA unescapeHtmlEntities() decodes entities, though we preserve raw too.
    return translated


def discover_pa_key(budget_seconds=35, timeout_seconds=4, max_scripts=12):
    """Same NoveLA WTR-Lab route, but bounded with per-request diagnostics.

    Ranking -> first novel -> chapter-1 -> _next JS; no alternate providers.
    """
    base = "https://wtr-lab.com"
    deadline = time.monotonic() + budget_seconds

    def fetch_step(url, title):
        left = deadline - time.monotonic()
        if left <= 0:
            raise TimeoutError("PA anahtar keşfi toplam süre sınırını aştı")
        print("[PA] " + title + " (istek sınırı: " + str(round(min(left, timeout_seconds), 1)) + " sn)", flush=True)
        return call(url, timeout=min(left, timeout_seconds))

    ranking = fetch_step(base + "/en/ranking/monthly", "1/3 Aylık sıralama sayfası")
    links = re.findall(r'''href=["']([^"']*/novel/[^"']+)["']''', ranking)
    if not links:
        raise RuntimeError("WTR-Lab sıralamasında /novel/ bağlantısı bulunamadı")
    novel_url = urllib.parse.urljoin(base, html.unescape(links[0])).split("?")[0].rstrip("/")
    page = fetch_step(novel_url + "/chapter-1", "2/3 Romanın ilk bölümü")
    pattern = re.compile(r'"X-Goog-API-Key"\s*:\s*"([^"\s]+)"')
    match = pattern.search(page)
    if match:
        print("[PA] Anahtar bölüm sayfasında bulundu (gizli tutuluyor)", flush=True)
        return match.group(1)

    scripts = re.findall(r'''<script[^>]+src=["']([^"']*/_next/[^"']+\.js[^"']*)["']''', page)
    scripts = [src for src in dict.fromkeys(scripts)
               if "_buildManifest" not in src and "_ssgManifest" not in src]
    print(f"[PA] 3/3 {len(scripts)} JS dosyası; en fazla {max_scripts} taranacak", flush=True)
    if not scripts:
        raise RuntimeError("NoveLA'nın taradığı _next JS dosyaları bulunamadı")
    for i, script in enumerate(scripts[:max_scripts], 1):
        try:
            text = fetch_step(urllib.parse.urljoin(base, html.unescape(script)),
                              f"JS {i}/{min(len(scripts), max_scripts)}")
            match = pattern.search(text)
            if match:
                print("[PA] Anahtar bulundu (gizli tutuluyor)", flush=True)
                return match.group(1)
        except (OSError, ValueError, RuntimeError, TimeoutError) as exc:
            print(f"[PA] JS {i} hatası: {type(exc).__name__}", flush=True)
        if time.monotonic() >= deadline:
            break
    raise RuntimeError("PA anahtarı süre/dosya sınırında bulunamadı; gerçek Enhanced testi yapılamadı")


def readable(result):
    result = re.sub(r"<br\s*/?>", "\n", result, flags=re.I)
    return html.unescape(re.sub(r"</?span\b[^>]*>", "", result, flags=re.I))


def classify_preservation(result, term, variant):
    visible = readable(result)
    unchanged = bool(re.search(r'(?<!\w)' + re.escape(term) + r'(?!\w)', visible, flags=re.I))
    if variant == "placeholders":
        return "placeholder_present" if "ZXQ0001ZXQ" in visible else "placeholder_lost"
    return "original_present" if unchanged else "original_absent"


def run(args):
    selected = VARIANTS if args.variants == "all" else tuple(args.variants.split(","))
    if any(v not in VARIANTS for v in selected):
        raise SystemExit("Geçersiz variant; seçenekler: " + ",".join(VARIANTS))
    providers = ("free", "pa") if args.provider == "both" else (args.provider,)
    pa_key = os.environ.get(args.pa_key_env, "")
    if "pa" in providers and not pa_key and args.discover_pa_key:
        print("PA anahtarı NoveLA yöntemine benzer şekilde genel sayfalardan deneniyor…", file=sys.stderr)
        try:
            pa_key = discover_pa_key(budget_seconds=args.discover_budget, timeout_seconds=args.discover_timeout, max_scripts=args.discover_max_scripts)
        except Exception as e:
            print("PA anahtar keşfi başarısız: " + str(e), file=sys.stderr)
    if "pa" in providers and not pa_key:
        print(f"UYARI: PA testleri anahtarsız atlanacak. ${args.pa_key_env} veya --discover-pa-key kullan.", file=sys.stderr)
    records = []
    for provider in providers:
        if provider == "pa" and not pa_key:
            continue
        for term, sentence in TEST_CASES[:args.cases]:
            for variant in selected:
                payload = transform(sentence, term, variant)
                row = {"provider": provider, "term": term, "variant": variant,
                       "original": sentence, "request_text": payload, "ok": False}
                try:
                    result = google_free(payload, args.source, args.target) if provider == "free" else google_pa(payload, args.source, args.target, pa_key)
                    row.update(ok=True, raw_translation=result, readable_translation=readable(result),
                               preservation=classify_preservation(result, term, variant))
                    print(f"[{provider}/{variant}] {term}: {row['readable_translation']}")
                except Exception as e:
                    row["error"] = str(e)
                    print(f"[{provider}/{variant}] {term}: HATA {e}")
                records.append(row)
                if args.pause:
                    time.sleep(args.pause)
    data = {"source_ref": "TheOsmanYILDIRIM/NoveLA_float@a468928910b6be17", "source_language": args.source,
            "target_language": args.target, "created_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
            "results": records}
    out = Path(args.output).expanduser()
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nRapor: {out} | başarılı {sum(r['ok'] for r in records)}/{len(records)}")
    print("Yorum: 'original_present' çevirinin doğru olduğunu kanıtlamaz; bağlam/ekler elle karşılaştırılmalı.")
    if not records or not any(r["ok"] for r in records):
        return 2
    return 0


def main():
    p = argparse.ArgumentParser(description="NoveLA Google Simple/Enhanced Termux glossary marker A/B test")
    p.add_argument("--provider", choices=("free", "pa", "both"), default="both")
    p.add_argument("--source", default="en")
    p.add_argument("--target", default="tr")
    p.add_argument("--cases", type=int, choices=range(1, 6), metavar="1-5", default=2)
    p.add_argument("--variants", default="all", help="all veya virgülle: " + ",".join(VARIANTS))
    p.add_argument("--pa-key-env", default="NOVELA_PA_KEY", help="PA anahtarını tutan çevre değişkeninin adı")
    p.add_argument("--discover-pa-key", action="store_true", help="NoveLA'nın WtrLab genel sayfa anahtar keşfini dene")
    p.add_argument("--discover-budget", type=float, default=35)
    p.add_argument("--discover-timeout", type=float, default=4)
    p.add_argument("--discover-max-scripts", type=int, default=12)
    p.add_argument("--pause", type=float, default=0.75)
    p.add_argument("--output", default="novela_google_probe_results.json")
    p.add_argument("--self-test", action="store_true", help="Ağa çıkmadan istek hazırlama ve ayrıştırıcı öz testleri")
    args = p.parse_args()
    if args.self_test:
        assert 'translate="no"' in transform(TEST_CASES[0][1], "Spirit Core", "translate_no")
        assert 'class="notranslate"' in transform(TEST_CASES[0][1], "Spirit Core", "class_notranslate")
        assert transform(TEST_CASES[0][1], "Spirit Core", "brackets").count("⟦Spirit Core⟧") == 1
        assert classify_preservation("<span>Spirit Core</span>", "Spirit Core", "translate_no") == "original_present"
        assert classify_preservation("Ruh Çekirdeği", "Spirit Core", "plain") == "original_absent"
        assert readable("Michael &amp; Alice<br>Hi") == "Michael & Alice\nHi"
        print("OK: çevrimdışı 6 temel öz test geçti; gerçek Google isteği yapılmadı")
        return 0
    if args.discover_budget <= 0 or args.discover_timeout <= 0 or args.discover_max_scripts < 1:
        p.error("PA sınırları pozitif olmalı")
    if args.pause < 0:
        p.error("--pause sıfırdan küçük olamaz")
    return run(args)


if __name__ == "__main__":
    sys.exit(main())