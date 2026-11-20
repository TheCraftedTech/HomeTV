package de.thecraftedtech.hometv.ui

/** Tasten der Fernbedienung, die in der Anleitung als Symbole gezeigt werden. */
enum class RemoteKey { UP, DOWN, LEFT, RIGHT, OK, BACK, MENU, PLAY_PAUSE, FF, REW, CH_UP, CH_DOWN }

/** Ein Tastensymbol; [long] = lange gedrueckt halten. */
data class KeyChip(val key: RemoteKey, val long: Boolean = false)

/** Eine Zeile der Anleitung: ein oder mehrere Tastensymbole (Alternativen) und was sie bewirken. */
data class HelpRow(val keys: List<KeyChip>, val text: String)

data class HelpPage(val title: String, val rows: List<HelpRow>)

/** Inhalt der Anleitung (reine Daten, ohne Android – testbar). Beruecksichtigt die Einstellung "Kanaltasten vertauschen". */
object HelpContent {

    private fun row(text: String, vararg keys: RemoteKey) = HelpRow(keys.map { KeyChip(it) }, text)
    private fun longRow(text: String, key: RemoteKey) = HelpRow(listOf(KeyChip(key, long = true)), text)

    fun pages(invertChannelKeys: Boolean): List<HelpPage> {
        val up = if (invertChannelKeys) "Vorheriger Sender" else "Nächster Sender"
        val down = if (invertChannelKeys) "Nächster Sender" else "Vorheriger Sender"
        return listOf(
            HelpPage("Fernsehbild", listOf(
                row("$up (halten = schneller, am Ende springt es um)", RemoteKey.UP, RemoteKey.CH_UP),
                row(down, RemoteKey.DOWN, RemoteKey.CH_DOWN),
                row("Senderliste öffnen", RemoteKey.RIGHT),
                row("Einstellungen öffnen", RemoteKey.LEFT),
                row("Info-Leiste mit aktueller Sendung ein/aus", RemoteKey.OK),
                longRow("Programmübersicht öffnen", RemoteKey.OK),
                row("Programmübersicht öffnen", RemoteKey.REW),
                row("Favoriten-Leiste öffnen", RemoteKey.FF),
                row("Einstellungen öffnen", RemoteKey.MENU),
                row("Standbild – erneut drücken: live neu starten", RemoteKey.PLAY_PAUSE),
                row("Info-Leiste schließen, sonst App beenden", RemoteKey.BACK),
            )),
            HelpPage("Senderliste", listOf(
                row("Sender wählen (am Rand springt es auf die andere Seite)", RemoteKey.UP, RemoteKey.DOWN),
                row("Sender ansehen", RemoteKey.OK),
                longRow("★ Favorit setzen oder entfernen", RemoteKey.OK),
                row("Nur Favoriten ein/aus", RemoteKey.MENU),
                row("Programmübersicht öffnen", RemoteKey.RIGHT),
                row("Liste schließen", RemoteKey.LEFT, RemoteKey.BACK),
            )),
            HelpPage("Programmübersicht", listOf(
                row("Sender wechseln (Zeit bleibt)", RemoteKey.UP, RemoteKey.DOWN),
                row("Vorige / nächste Sendung", RemoteKey.LEFT, RemoteKey.RIGHT),
                row("3 Stunden zurück / vor", RemoteKey.REW, RemoteKey.FF),
                row("Sender ansehen", RemoteKey.OK),
                longRow("★ Favorit setzen oder entfernen", RemoteKey.OK),
                row("Nur Favoriten ein/aus", RemoteKey.MENU),
                row("Schließen", RemoteKey.BACK),
            )),
            HelpPage("Favoriten-Leiste", listOf(
                row("Im Fernsehbild öffnen", RemoteKey.FF),
                row("Favorit wählen (am Rand springt es um)", RemoteKey.LEFT, RemoteKey.RIGHT),
                row("Sender ansehen", RemoteKey.OK),
                longRow("Aus den Favoriten entfernen", RemoteKey.OK),
                row("Schließen", RemoteKey.BACK),
            )),
            HelpPage("Einstellungen", listOf(
                row("Bereich bzw. Eintrag markieren (ändert nichts)", RemoteKey.UP, RemoteKey.DOWN),
                row("Bereich öffnen", RemoteKey.RIGHT, RemoteKey.OK),
                row("Eintrag anklicken: Schalter umlegen, Wert bearbeiten, Text eingeben", RemoteKey.OK),
                row("Im Bearbeiten-Modus (amber): Wert ändern", RemoteKey.LEFT, RemoteKey.RIGHT),
                row("Bearbeiten beenden", RemoteKey.OK, RemoteKey.BACK),
                row("Eine Ebene zurück – aus der Bereichsliste: schließen", RemoteKey.LEFT, RemoteKey.BACK),
                row("Einstellungen sofort schließen", RemoteKey.MENU),
            )),
        )
    }
}
