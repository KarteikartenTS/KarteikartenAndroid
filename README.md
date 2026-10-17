# 📚 Karteikarten der TÜV SÜD Akademie

## Discontinued

Auf der Seite die Datei **`TUEV-SUED-Akademie-Karteikarten-0.1.0-demo.apk`** antippen, öffnen und installieren.
Fragt Android, ob „unbekannte Apps“ installiert werden dürfen: erlauben.

## 👉 [iPhone / iPad: direkt im Browser lernen](https://karteikartents.github.io/KarteikartenInhalte/)

In **Safari** öffnen → **Teilen** → **„Zum Home-Bildschirm“**.

> Demo-Version mit dem Modul **PTI 1**. Die Karten sind unvollständig und dienen nur dem Kennenlernen.

---

## Für Entwickler: Karteikarten-App der TÜV SÜD Akademie

Android-App zum Lernen von Karteikarten für Teilnehmende der TÜV SÜD Akademie. Die App lädt die Karteikarten-Module
von einer festen Adresse und hält sie automatisch aktuell. Der Lernstand bleibt dabei erhalten.

Status: Prototyp.

## Lizenz und Herkunft

Diese App basiert auf dem Quellcode von AnkiDroid (Version 2.25.1) und steht wie dieser unter der
**GNU General Public License v3.0** (siehe `COPYING` und `LICENSES/`). Die Copyright-Hinweise der ursprünglichen
Autorinnen und Autoren in den Quelldateien bleiben erhalten. Diese App ist kein offizielles AnkiDroid-Produkt und
wird nicht vom AnkiDroid-Projekt unterstützt.

## Bauen

Voraussetzungen: Android SDK, JDK 21 bis 25 (z. B. das JDK aus Android Studio).

```
./gradlew :AnkiDroid:assemblePlayDebug
```

Die APKs liegen danach unter `AnkiDroid/build/outputs/apk/play/debug/`.
