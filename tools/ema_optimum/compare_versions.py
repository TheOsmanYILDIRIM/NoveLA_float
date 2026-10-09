"""Generate matched 48 kHz samples from original EMA and two ONNX conversions."""
import json, time, wave
from pathlib import Path
import numpy as np
import torch
import onnxruntime as ort
from huggingface_hub import hf_hub_download
from ema_lightning.model import load_acoustic
from ema_lightning.decoder import load_decoder
from ema_lightning.engine import Engine, windows, FIRST_WINDOW, CONTEXT
from ema_lightning.frontend import Frontend
from quality import from_onnx_durations

OUT=Path("dist/ema-comparison")
OUT.mkdir(parents=True, exist_ok=True)
MODEL="canberkkkkkk/ema-lightning"
SOURCE_REV="7a6ba1ad216bb2f1da9863f80ac8770a6a807632"
STAGES=("text_stage","sound_stage","decoder")
TESTS=(("greeting","Merhaba! Bugün hava güzel, değil mi?",1.0,42),
       ("numbers","2026 yılında saat 14.30'da, bin iki yüz elli lira ödendi.",1.2,1729),
       ("novel","Kapıyı yavaşça araladı. Bir an durup sessiz sokağa baktı.",1.0,53))

def session(path):
    options=ort.SessionOptions()
    options.intra_op_num_threads=4
    return ort.InferenceSession(str(path),sess_options=options,providers=["CPUExecutionProvider"])

def infer(sess,**kwargs):
    return sess.run(None,{k:(v.numpy() if isinstance(v,torch.Tensor) else v) for k,v in kwargs.items()})

def save(path,data):
    data=np.clip(data,-1,1)
    with wave.open(str(path),"wb") as wav:
        wav.setnchannels(1);wav.setsampwidth(2);wav.setframerate(48000)
        wav.writeframes((data*32767).astype("<i2").tobytes())

def score(ref,other):
    assert ref.shape==other.shape
    error=other.astype(np.float64)-ref.astype(np.float64)
    mse=np.mean(error**2)
    return {"snr_db":round(float(10*np.log10(np.mean(ref.astype(np.float64)**2)/max(mse,1e-18))),3),
            "rmse":round(float(np.sqrt(mse)),9),
            "max_abs":round(float(np.max(np.abs(error))),9)}

torch.set_num_threads(4)
acoustic=load_acoustic(hf_hub_download(MODEL,"ema.pt",revision=SOURCE_REV),torch.device("cpu"))
decoder=load_decoder(hf_hub_download(MODEL,"decoder.pt",revision=SOURCE_REV),torch.device("cpu"))
engine=Engine(acoustic,decoder,torch.device("cpu"))
frontend=Frontend(acoustic.vocab)
ours=Path("dist/ema-optimum")
folders={"ours":{n:ours/f"{n}.onnx" for n in STAGES},
         "web":{n:Path(hf_hub_download("fr0stb1rd/ema-lightning-web-onnx",f"{n}.onnx")) for n in STAGES}}
sessions={label:{n:session(p) for n,p in paths.items()} for label,paths in folders.items()}
report={"source_revision":SOURCE_REV,"model_bytes":{label:{n:p.stat().st_size for n,p in paths.items()} for label,paths in folders.items()},
        "method":"identical original Turkish frontend, original planning, seeded torch noise, contextual decoder windows","tests":[]}
for label,text,speed,seed in TESTS:
    normalized=frontend(text)
    piece=engine.piece(normalized,0.0,seed)
    started=time.perf_counter()
    engine.plan([piece],speed);engine.think([piece])
    original=np.concatenate([engine.decode([(piece,span)])[0].cpu().numpy() for span in windows(piece.frames,FIRST_WINDOW)])
    timing={"original":round(time.perf_counter()-started,4)}
    audio={"original":original}
    for variant,ss in sessions.items():
        start=time.perf_counter()
        h,d=infer(ss["text_stage"],ids=piece.ids[None],mask=torch.ones((1,piece.letters),dtype=torch.bool))
        durations=(d[0]/speed).astype(np.float32)
        fw,fp=from_onnx_durations(piece,durations)
        assert torch.equal(fw,piece.fw) and torch.allclose(fp,piece.fp,atol=1e-6,rtol=1e-6)
        (z,)=infer(ss["sound_stage"],h=h,dur=durations[None],
              mask=np.ones((1,piece.letters),dtype=np.bool_),cw=piece.cw[None],
              wstart=piece.wstart[None],fw=fw[None],fp=fp[None],
              fmask=torch.ones((1,piece.frames),dtype=torch.bool),noise=engine.noise(piece)[None])
        chunks=[]
        for a,b in windows(piece.frames,FIRST_WINDOW):
            begin,end=max(0,a-CONTEXT),min(piece.frames,b+CONTEXT)
            (raw,)=infer(ss["decoder"],z=z[0,begin:end].T[None].astype(np.float32))
            chunks.append(raw[0,(a-begin)*decoder.hop:(b-begin)*decoder.hop])
        audio[variant]=np.concatenate(chunks)
        timing[variant]=round(time.perf_counter()-start,4)
    comparison={variant:score(original,audio[variant]) for variant in ("ours","web")}
    report["tests"].append({"id":label,"text":text,"normalized":normalized,"speed":speed,
                            "audio_seconds":round(len(original)/48000,3),
                            "scores":comparison,"wall_seconds":timing})
    for variant,samples in audio.items():
        save(OUT/f"{label}_{variant}.wav",samples)
    print(f"{label}: ours SNR {comparison['ours']['snr_db']} dB, web SNR {comparison['web']['snr_db']} dB",flush=True)
(OUT/"comparison.json").write_text(json.dumps(report,ensure_ascii=False,indent=2))
print("Generated 9 WAV examples; comparison.json contains actual metrics.",flush=True)
