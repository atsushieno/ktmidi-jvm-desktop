# ktmidi JVM desktop implementation - ALSA

This repository is part of [ktmidi](https://github.com/atsushieno/ktmidi) family `ktmidi-jvm-desktop` package. It includes ALSA support that depends on [alsakt](https://github.com/atsushieno/alsakt) package, and split from ktmidi project itself to avoid extraneous dependencies on non-Linux platform.

`AlsaMidiAccess` supports virtual MIDI ports which is very useful when you want your app act as virtual MIDI devices. [atsushieno/kmmk](https://github.com/atsushieno/kmmk) makes full use of it.

(It is technically doable with CoreMIDI, and if `CoreMidiAccess` existed it would be similarly useful. Contribution highly appreciated.)
