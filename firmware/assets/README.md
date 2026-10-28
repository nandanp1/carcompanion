# Navigation phrase bank

`navigation.pcm` contains original generic navigation phrases synthesized using
[eSpeak NG](https://github.com/espeak-ng/espeak-ng) 1.52 (`en-us`, 155 words/minute)
and converted with ffmpeg to signed 16-bit little-endian, 16 kHz mono PCM.
The text and generation procedure are in `tools/generate_navigation_voice.py`;
clip offsets are in `include/VoiceClipData.h`.

Only generated speech is embedded: no eSpeak program, voice database, Google
recording, street-name audio or network speech service is included. eSpeak NG
is a GPL-3.0-or-later build tool; its source and license are available at the
linked project. Normal firmware builds use the committed PCM directly.
