# PDF Leser – PDFs lesen wie ein E-Book

Native Android-App (Kotlin) für dein Samsung Galaxy XCover7 (läuft auf jedem Android ab Version 10). PDFs werden in echten Fließtext
umgewandelt, sodass Schriftgröße, Schriftart und Farben frei einstellbar sind –
ähnlich wie in der Kobo-App.

## Funktionen

- **Lesemodus (Fließtext):** Text wird neu umbrochen, mit Blocksatz und Silbentrennung.
- **Nachtmodus:** Farbschemata Hell, Sepia, Dunkel und **Nacht (weiße Schrift auf Schwarz)**.
- **Schriftgröße** 12–40 sp, Schriftart, Zeilenabstand, Seitenrand, Helligkeit, Vollbild.
- **Nachschlagen:** Wort lange drücken (ziehen = mehrere Wörter) → Karte mit
  - deutscher Übersetzung inkl. weiterer Bedeutungen nach Wortart,
  - **englischer Wörterbuch-Definition** mit Aussprache (IPA), Wortart und Beispielsätzen
    (findet auch Grundformen: „running“ → „run“, „studies“ → „study“),
  - 🔊 Aussprechen, ☆ Vokabelliste, „Markieren“, „Kopieren“, „Google“.
- **Vokabelliste:** Nachgeschlagene Wörter werden automatisch mit Übersetzung, Definition und
  dem Satz, in dem sie standen, gespeichert (abschaltbar). Durchsuchbar, antippen = aussprechen,
  lange drücken = löschen, **Export als CSV** (Excel, Google Tabellen, Anki).
  Erreichbar über das Hut-Symbol in der Bibliothek und im Lesemenü.
- **Textmarkierungen:** Auswahl → „Markieren“. Tippen auf eine Markierung öffnet sie wieder
  (dort „Markierung entfernen“).
- **Lesezeichen:** Lesezeichen-Symbol oben im Menü; markierte Seiten tragen ein rotes Band.
- **Inhalt · Lesezeichen · Markierungen:** Listen-Symbol oben im Menü; Eintrag antippen = hinspringen,
  lange drücken = löschen.
- **Vorlesen:** Kopfhörer-Symbol im Menü. Liest ab der aktuellen Seite Satz für Satz vor, hebt den
  Satz hervor und blättert automatisch weiter. Pause, Tempo (0,8×–1,8×) und Stopp in der kleinen
  Leiste unten. Während des Vorlesens regeln die Lautstärketasten die Lautstärke.
- **Bilder & Grafiken** aus dem PDF an der richtigen Stelle im Text; Fußnoten werden erkannt.
- **Originalansicht** (Knopf „Original“), bei Scans automatisch.
- **Absätze pro Buch:** unter „Aa“ → *Absätze (dieses Buch)*: „Automatisch“ oder „Aus (Fließtext)“
  für PDFs, deren Absätze sich nicht sauber erkennen lassen. Das Buch wird dann neu aufbereitet,
  Stelle, Lesezeichen und Markierungen bleiben erhalten.
- Blättern per Tippen (links/rechts), Wischen oder Lautstärketasten; Mitte = Menü.

## Performance (abgestimmt auf Mittelklasse-Chips wie im XCover7)

- Text wird **einmalig im Hintergrund** extrahiert und auf dem Gerät gespeichert; danach öffnet
  sich das Buch sofort. Man kann schon lesen, während der Rest noch aufbereitet wird.
- Die Aufbereitung läuft auf einem eigenen Thread mit niedrigerer Priorität, damit Blättern
  und Zeichnen immer Vorrang haben.
- Der Seitenumbruch wird pro Buch und Darstellung **gespeichert**: Beim erneuten Öffnen
  (gleiche Schriftgröße/Schrift/Ränder) muss nichts neu berechnet werden.
- Schnelle Silbentrennung ab Android 13, Umbruch im Hintergrund, Seiten werden direkt auf die
  Canvas gezeichnet (keine WebView), Layouts und Bilder gecacht und in Bildschirmgröße gerendert.

## Bauen und installieren (Windows-Laptop → Galaxy XCover7)

1. **Android Studio** installieren: https://developer.android.com/studio
2. ZIP entpacken, in Android Studio **File → Open** → Ordner `PdfLeser` wählen.
   Beim ersten Öffnen lädt Android Studio Gradle und alle Bibliotheken (Internet nötig, einige Minuten).
   Falls gefragt wird, ob Android SDK 35 installiert werden soll: ja.
3. **XCover7 vorbereiten:**
   - Einstellungen → **Telefoninfo → Softwareinformationen** → 7× auf **„Buildnummer"** tippen
     (PIN eingeben) → „Entwicklermodus aktiviert".
   - Einstellungen → **Entwickleroptionen** (ganz unten) → **USB-Debugging** einschalten.
   - Einstellungen → **Sicherheit und Datenschutz → Automatische Sperre**: falls eingeschaltet,
     **ausschalten** – sonst blockiert Samsung die Installation per USB und aus APK-Dateien.
     Nach der Installation kannst du sie wieder einschalten, die App läuft trotzdem.
4. XCover7 per USB-Kabel verbinden, auf dem Handy „USB-Debugging zulassen" bestätigen.
   (Bei Windows fehlt manchmal der Treiber: „Samsung USB Driver for Mobile Phones" von
   developer.samsung.com installieren.)
5. Oben in Android Studio das XCover7 auswählen und auf **▶ Run** klicken – die App wird installiert.

**Ohne Kabel/Debugging:** Build → Build App Bundle(s) / APK(s) → **Build APK(s)**.
Die Datei `app/build/outputs/apk/debug/app-debug.apk` aufs Handy kopieren (z. B. über Google Drive),
in „Eigene Dateien" antippen und „Installation aus dieser Quelle zulassen" bestätigen
(Automatische Sperre muss dafür aus sein, siehe oben).

## Updates ohne Kabel (GitHub)

Die App kann sich selbst aktualisieren. GitHub baut bei jeder Änderung automatisch eine neue
Version; die App meldet sie beim Start („Update verfügbar“) und installiert sie auf Knopfdruck.

### Einmalige Einrichtung (ca. 15 Minuten, am Laptop)

1. Auf **github.com** ein kostenloses Konto anlegen und **GitHub Desktop** installieren
   (desktop.github.com), dort anmelden.
2. GitHub Desktop → *File → Add local repository* → den Ordner `PdfLeser` wählen →
   „create a repository“ → *Create repository* → *Publish repository*.
   **Wichtig:** Haken bei „Keep this code private“ entfernen (öffentlich), sonst kann die App
   die Updates nicht ohne Passwort laden. Deine PDFs und Vokabeln sind nicht im Code – die bleiben auf dem Handy.
3. Signaturschlüssel hinterlegen (damit Android das Update als dieselbe App erkennt):
   - Windows-Taste → „PowerShell“ öffnen → diese Zeile einfügen und Enter:
     `[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore")) | Set-Clipboard`
     (kopiert den Schlüssel in die Zwischenablage)
   - Auf github.com dein Repository öffnen → *Settings → Secrets and variables → Actions →
     New repository secret* → Name: `DEBUG_KEYSTORE`, Inhalt: einfügen → *Add secret*.
4. Reiter *Actions* → „App bauen“ → *Run workflow*. Nach ca. 5 Minuten erscheint rechts unter
   *Releases* die fertige Version (der allererste Lauf direkt nach dem Hochladen schlägt fehl,
   solange der Schlüssel noch fehlt – das ist normal).
5. Ein letztes Mal per Kabel mit ▶ Run installieren. Dann in der App in der Bibliothek oben rechts
   ⋮ → **Update-Quelle festlegen** → `deinname/PdfLeser` eintragen.

### So läuft ein Update danach

1. Neue ZIP entpacken und den Inhalt in deinen `PdfLeser`-Ordner kopieren (Dateien ersetzen).
2. GitHub Desktop: unten kurz beschreiben (z. B. „Wörterbuch“) → *Commit to main* → *Push origin*.
3. Ca. 5 Minuten warten, App öffnen → „Update verfügbar“ → **Installieren**.
   Beim allerersten Mal fragt Android, ob der PDF Leser Apps installieren darf →
   „Aus dieser Quelle erlauben“ einschalten und zurückgehen.

Hinweise: Samsungs **Automatische Sperre** muss dafür ausgeschaltet bleiben (Einstellungen →
Sicherheit und Datenschutz). Bücher, Vokabeln, Lesezeichen und Einstellungen bleiben bei Updates
erhalten. Meldet Android „App nicht installiert … Konflikt“, wurde mit einem anderen Schlüssel gebaut
(Schritt 3 prüfen). Manuell prüfen: ⋮ → *Nach Updates suchen*.

## Hinweise

- Übersetzung und Wörterbuch brauchen Internet (Google Übersetzer, Free Dictionary API,
  Wiktionary), aber **keine** Google-Übersetzer-App. Nur der Knopf
  „Google Übersetzer" nutzt die App, falls installiert (sonst öffnet er den Browser).
- Blättern geht auch mit den Lautstärketasten (abschaltbar unter „Darstellung").
- Vorlesen nutzt die Sprachausgabe des Handys. Fehlt eine Stimme, meldet die App das:
  in den Einstellungen nach „Text-in-Sprache“ suchen und Englisch/Deutsch herunterladen.
- Sprache der Übersetzung: Ausgangssprache ist Englisch, Zielsprache Deutsch
  (änderbar in `Translator.kt`, Konstanten `SOURCE` / `TARGET`).
- Bei ungewöhnlich gesetzten PDFs (mehrspaltige Fachartikel, Tabellen) kann die Absatzerkennung
  im Lesemodus Fehler machen – dann hilft die Originalansicht.
- Passwortgeschützte PDFs werden nicht unterstützt.

## Aufbau des Codes

| Datei | Aufgabe |
|---|---|
| `MainActivity.kt` | Bibliothek, Import |
| `ReaderActivity.kt` | Leseansicht, Menüs, Einstellungen, Übersetzungskarte |
| `ReaderPageView.kt` | Zeichnet eine Seite, Tippen & Wortmarkierung |
| `Extractor.kt` | PDF → Absätze/Überschriften/Abbildungen (PDFBox), Disk-Cache |
| `Layouts.kt` | Seitenumbruch (StaticLayout, Hurenkinder/Schusterjungen-Regeln) |
| `ReaderViewModel.kt` | Zustand, Hintergrund-Paginierung, Bild-Cache |
| `PdfPages.kt` | Rendering über Androids PdfRenderer |
| `Translator.kt` | Google-Übersetzung |
