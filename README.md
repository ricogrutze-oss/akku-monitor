# Akku-Monitor

Schlanke Android-App ohne Werbung, ohne Internet-Berechtigung und ohne Fremdbibliotheken.
Sie misst im Hintergrund Ladestrom, Entladestrom, Leistung, Spannung und Temperatur und
errechnet daraus die tatsächliche Akkukapazität.

## Installation (ohne Android Studio)

1. Neues GitHub-Repo anlegen, z. B. `akku-monitor` (gern privat).
2. Inhalt dieses Ordners hochladen, inklusive `.github/` und `app/akkumonitor.jks`.
   (Die Weboberfläche zeigt versteckte Ordner wie `.github` manchmal nicht an. Dann
   die Datei `.github/workflows/build.yml` über „Add file → Create new file“ mit genau diesem Pfad anlegen.)
3. Unter **Actions** startet automatisch „APK bauen“ (ca. 3–5 Minuten).
4. Danach unter **Releases** am Handy `AkkuMonitor.apk` herunterladen und öffnen.
   Einmalig „Installation aus unbekannten Quellen“ für den Browser erlauben.
5. Beim ersten Start Benachrichtigungen erlauben und unten
   **„Akku-Optimierung für diese App ausschalten“** antippen, sonst beendet Android die Messung irgendwann.

Neue Versionen: Code ändern und pushen, dann die neue APK über die alte installieren. Die Daten bleiben erhalten,
weil alle Versionen mit demselben Schlüssel (`app/akkumonitor.jks`) signiert sind.
Das Passwort steht offen in `app/build.gradle.kts`. Für ein privates Werkzeug ist das unkritisch.

## So wird die Kapazität berechnet

* Alle 10 s (einstellbar) wird `BATTERY_PROPERTY_CURRENT_NOW` gelesen. Einheit (µA oder mA) und
  Vorzeichen erkennt die App selbst, weil das jeder Hersteller anders macht.
* Der Strom wird über die Zeit aufintegriert (Coulomb-Zählung, Trapezregel) und ergibt die Ladung in mAh.
* Gezählt wird nur zwischen zwei **Prozentsprüngen** der Akkuanzeige. Damit fällt die Rundung
  der ganzzahligen Prozentanzeige weg.
* Kapazität = ΔmAh / Δ% · 100. Gespeichert wird ein Wert, sobald ein Lade- oder Entladeabschnitt
  mindestens 10 % umfasst. Angezeigt wird ein gewichtetes Mittel der letzten 10 Werte.
* Beim Laden hält die App die CPU wach, damit die Messung lückenlos ist (kostet nichts, da das Netzteil dran ist).
  Beim Entladen schläft das Handy. Lücken überbrückt die App dann mit dem Ladungszähler des Fuel-Gauge-Chips, falls das
  Gerät ihn liefert. Sonst werden sie interpoliert und der Wert wird als „ungenau (~)“ markiert.

**Genaueste Messung:** Akku auf ca. 15–20 % entladen und dann am Stück bis 100 % laden.

## Grenzen

* Gemessen wird der Strom am Akku, nicht am Ladekabel. Ladeverluste im Handy sind also nicht enthalten.
  Für die Kapazität ist das genau richtig.
* Die Prozentanzeige stammt selbst vom Fuel-Gauge des Handys. Ist dieser stark falsch kalibriert, wirkt sich das aus.
  Eine Vollladung bis 100 % kalibriert ihn meist wieder.
* Manche Geräte liefern gar keinen Stromwert (Anzeige „–“) oder ihn nur grob gerastert.
* „Nennkapazität vom System“ kommt aus einer internen Android-Schnittstelle. Wenn nichts angezeigt wird,
  den Wert aus dem Datenblatt eintragen.

## Dateien

| Datei | Inhalt |
|---|---|
| `Battery.kt` | Auslesen, Einheiten-/Vorzeichen-Erkennung, Nennkapazität |
| `Tracker.kt` | Coulomb-Zählung und Kapazitätsschätzung |
| `MonitorService.kt` | Hintergrundmessung, Benachrichtigung mit Live-Werten |
| `DataLog.kt` | Tagesdateien (60 Tage), CSV-Export für Excel |
| `ChartView.kt` | Diagramm Strom + Ladestand |
| `MainActivity.kt` | Oberfläche |

## Version 1.1

* Ladegrenze-Alarm (80/85/90/100 %) mit Ton und Vibration
* Temperaturwarnung (38–45 °C)
* Ladevorgänge mit max. Strom/Leistung, Ladekurve (Strom, Spannung, Temperatur) und Namen für Ladegeräte-Vergleich
* Verbrauch getrennt nach Display an/aus (Standby-Verbrauch)
* Restlaufzeit bzw. Zeit bis voll
* Langzeitverlauf der Kapazität und Ladezyklen
* Startbildschirm-Widget
* Apps: Verbrauch je Vordergrund-App bei Display an, aktive Hintergrund-Apps bei Display aus (braucht Nutzungsdatenzugriff)
