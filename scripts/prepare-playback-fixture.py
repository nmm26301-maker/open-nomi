#!/usr/bin/env python3
"""Fetch the exact Apache-2.0 Vosk public test WAV for an external player UID."""
import hashlib
from pathlib import Path
import urllib.request
import wave

root = Path(__file__).resolve().parents[1]
url = 'https://raw.githubusercontent.com/alphacep/vosk-api/05adbfcc0df27a1535913c6accd4b7fc60ffd59d/python/example/test.wav'
with urllib.request.urlopen(url, timeout=90) as response:
    data = response.read(300000)
if len(data) != 265914 or hashlib.sha1(b'blob 265914\0' + data).hexdigest() != 'c41144a21710590e568e4e612d2a40baf9a71223':
    raise ValueError('Public speech fixture differs from the pinned Vosk blob')
path = root / 'playback-fixture/src/main/res/raw/speech.wav'
path.parent.mkdir(parents=True, exist_ok=True)
path.write_bytes(data)
with wave.open(str(path)) as wav:
    if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (1, 2, 16000):
        raise ValueError('Speech fixture format differs from 16 kHz mono PCM16')
print('Pinned public speech fixture SHA-256:', hashlib.sha256(data).hexdigest())

# Public continuous speech, JFK's 1961 inaugural address excerpt distributed by whisper.cpp.
quality_url = 'https://raw.githubusercontent.com/ggml-org/whisper.cpp/b0a11594aec50892a02cd8d129eee2dfe93a8bb8/samples/jfk.wav'
with urllib.request.urlopen(quality_url, timeout=90) as response:
    quality_data = response.read(400000)
if len(quality_data) != 352078 or hashlib.sha1(b'blob 352078\0' + quality_data).hexdigest() != '3184d372cd2f8b804d3a540c70ec50d927b335d2':
    raise ValueError('Natural speech fixture differs from the pinned whisper.cpp blob')
import io
with wave.open(io.BytesIO(quality_data)) as wav:
    if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (1, 2, 16000):
        raise ValueError('Natural speech fixture must be 16 kHz mono PCM16')
    pcm = wav.readframes(wav.getnframes())
quality_path = root / 'app/src/androidTest/assets/quality-jfk.pcm'
quality_path.parent.mkdir(parents=True, exist_ok=True)
quality_path.write_bytes(pcm)
print('Pinned natural speech WAV SHA-256:', hashlib.sha256(quality_data).hexdigest())
