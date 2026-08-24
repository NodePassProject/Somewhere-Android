# Third-party notices

Somewhere is licensed under GPL-3.0-only. Some files distributed with it are
not: they carry their own licence, and are listed here because the binary ships
them.

## Bundled fonts

Both families are under the **SIL Open Font License, Version 1.1**, which is
compatible with GPL-3.0 and permits bundling in an application. The full licence
text for each is in [`licences/`](licences/).

| Family | Files | Copyright | Licence |
|---|---|---|---|
| Archivo | `app/src/main/res/font/archivo_variable.ttf` | © 2020 The Archivo Project Authors | [OFL-1.1](licences/OFL-1.1-Archivo.txt) |
| IBM Plex Sans | `app/src/main/res/font/plex_sans_variable.ttf` | © 2017 IBM Corp., Reserved Font Name "Plex" | [OFL-1.1](licences/OFL-1.1-IBMPlex.txt) |
| IBM Plex Mono | `app/src/main/res/font/plex_mono_{regular,medium}.ttf` | © 2017 IBM Corp., Reserved Font Name "Plex" | [OFL-1.1](licences/OFL-1.1-IBMPlex.txt) |

The OFL's Reserved Font Name clause means the files must not be redistributed
under the name "Plex" if modified. They are shipped unmodified, under their
original names.

## Icons

The icon set in `app/src/main/kotlin/eu/nodepass/somewhere/ui/icons/` is drawn
from [Lucide](https://lucide.dev) path data, which is ISC-licensed. The vectors
are rebuilt in Kotlin rather than vendored, so no Lucide file ships; the path
geometry is acknowledged here.

## Runtime dependencies

Declared in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). AndroidX
and Kotlin libraries are Apache-2.0; Conscrypt is Apache-2.0. None are bundled
in source form.
