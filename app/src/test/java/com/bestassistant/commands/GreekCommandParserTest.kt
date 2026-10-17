package com.bestassistant.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class GreekCommandParserTest {

    /** Saturday 17 Oct 2026, 08:15 in the morning. */
    private val morning = LocalDateTime.of(2026, 10, 17, 8, 15)

    // Through CommandParser, as the app calls it: Greek text is routed to the Greek rules.
    private fun parse(text: String, now: LocalDateTime = morning) = CommandParser.parse(text, now)

    private inline fun <reified T : Command> expect(text: String, now: LocalDateTime = morning): T {
        val command = parse(text, now)
        assertTrue("expected ${T::class.simpleName} for '$text', got $command", command is T)
        return command as T
    }

    @Test
    fun alarms() {
        assertEquals(Command.SetAlarm(7, 0), expect<Command.SetAlarm>("βάλε ξυπνητήρι στις 7 το πρωί"))
        // At 08:15, "7 και μισή" next comes round in the evening.
        assertEquals(Command.SetAlarm(19, 30), expect<Command.SetAlarm>("ξύπνα με στις 7 και μισή"))
        assertEquals(Command.SetAlarm(6, 45), expect<Command.SetAlarm>("ξυπνητήρι στις 7 παρά τέταρτο το πρωί"))
        assertEquals(Command.SetAlarm(21, 0), expect<Command.SetAlarm>("ξυπνητήρι στις 9 το βράδυ"))
        assertEquals(Command.SetAlarm(6, 30), expect<Command.SetAlarm>("Βάλε ένα ξυπνητήρι για αύριο στις 6:30"))
        assertEquals(Command.SetAlarm(7, 0), expect<Command.SetAlarm>("ξυπνητήρι στις επτά το πρωί"))
        assertEquals(Command.SetAlarm(22, 15), expect<Command.SetAlarm>("ξυπνητήρι 22:15"))
        assertEquals(Command.SetAlarm(7, 10), expect<Command.SetAlarm>("ξύπνησέ με στις 7 και 10 π.μ."))
        // At 08:15, "7" next comes round at 19:00.
        assertEquals(19, expect<Command.SetAlarm>("ξυπνητήρι στις 7").hour)
        assertEquals(Command.SetAlarm(12, 0), expect<Command.SetAlarm>("ξυπνητήρι το μεσημέρι"))
    }

    @Test
    fun relativeAlarmsAndLabels() {
        assertEquals(Command.SetAlarm(8, 35), expect<Command.SetAlarm>("ξύπνα με σε 20 λεπτά"))
        assertEquals(Command.SetAlarm(9, 15), expect<Command.SetAlarm>("βάλε ξυπνητήρι σε μία ώρα"))
        assertEquals(Command.SetAlarm(6, 0, "γυμναστήριο"), expect<Command.SetAlarm>("ξυπνητήρι στις 6 το πρωί για γυμναστήριο"))
        assertEquals(Command.ShowAlarms, parse("δείξε τα ξυπνητήρια"))
    }

    @Test
    fun timers() {
        assertEquals(Command.SetTimer(600), expect<Command.SetTimer>("χρονόμετρο 10 λεπτά"))
        assertEquals(Command.SetTimer(300), expect<Command.SetTimer>("βάλε χρονόμετρο για πέντε λεπτά"))
        assertEquals(Command.SetTimer(1800), expect<Command.SetTimer>("χρονόμετρο μισή ώρα"))
        assertEquals(Command.SetTimer(5400), expect<Command.SetTimer>("χρονόμετρο για μιάμιση ώρα"))
        assertEquals(Command.SetTimer(5400), expect<Command.SetTimer>("χρονόμετρο μία ώρα και 30 λεπτά"))
        assertEquals(Command.SetTimer(9000), expect<Command.SetTimer>("χρονόμετρο δυόμισι ώρες"))
        assertEquals(Command.SetTimer(900), expect<Command.SetTimer>("αντίστροφη μέτρηση ένα τέταρτο"))
        assertEquals(Command.SetTimer(1500), expect<Command.SetTimer>("χρονόμετρο είκοσι πέντε λεπτά"))
        assertEquals(Command.SetTimer(45), expect<Command.SetTimer>("χρονόμετρο 45 δευτερόλεπτα"))
        assertEquals(Command.SetTimer(600, "μακαρόνια"), expect<Command.SetTimer>("βάλε χρονόμετρο 10 λεπτά για τα μακαρόνια"))
        assertEquals(Command.SetTimer(300), expect<Command.SetTimer>("χρονόμετρο 5"))
        assertEquals(Command.ShowTimers, parse("δείξε τα χρονόμετρα"))
        // "ένα" is an article here, not a one-minute timer.
        assertNull(parse("βάλε ένα χρονόμετρο"))
    }

    @Test
    fun torchAndVolume() {
        assertEquals(Command.Flashlight(Switch.ON), parse("άναψε τον φακό"))
        assertEquals(Command.Flashlight(Switch.OFF), parse("σβήσε τον φακό"))
        assertEquals(Command.Flashlight(Switch.TOGGLE), parse("φακός"))
        assertEquals(Command.Volume(VolumeChange.Up), parse("δυνάμωσε"))
        assertEquals(Command.Volume(VolumeChange.Down), parse("χαμήλωσε την ένταση"))
        assertEquals(Command.Volume(VolumeChange.Mute), parse("σίγαση"))
        assertEquals(Command.Volume(VolumeChange.Unmute), parse("άνοιξε τον ήχο"))
        assertEquals(Command.Volume(VolumeChange.Set(50)), parse("ένταση στο 50"))
        assertEquals(Command.Volume(VolumeChange.Set(40)), parse("βάλε την ένταση στο σαράντα τοις εκατό"))
        assertEquals(Command.Volume(VolumeChange.Set(100)), parse("ένταση στο τέρμα"))
    }

    @Test
    fun media() {
        assertEquals(Command.Media(MediaAction.PAUSE), parse("παύση"))
        assertEquals(Command.Media(MediaAction.PAUSE), parse("σταμάτα τη μουσική"))
        assertEquals(Command.Media(MediaAction.PLAY), parse("συνέχισε"))
        assertEquals(Command.Media(MediaAction.PLAY), parse("παίξε μουσική"))
        assertEquals(Command.Media(MediaAction.NEXT), parse("επόμενο τραγούδι"))
        assertEquals(Command.Media(MediaAction.PREVIOUS), parse("προηγούμενο κομμάτι"))
        assertEquals(Command.NowPlaying, parse("τι παίζει;"))
        assertEquals(Command.NowPlaying, parse("ποιο τραγούδι είναι αυτό"))
        assertEquals(Command.LikeSong, parse("μου αρέσει αυτό το τραγούδι"))
        assertEquals(Command.Shuffle(true), parse("τυχαία αναπαραγωγή"))
        assertEquals(Command.Repeat(false), parse("σταμάτα την επανάληψη"))
    }

    @Test
    fun music() {
        assertEquals(Command.PlayMusic("iron maiden", MusicApp.SPOTIFY), parse("παίξε Iron Maiden στο Spotify"))
        assertEquals(Command.PlayMusic("βασίλη παπακωνσταντίνου", MusicApp.SPOTIFY), parse("βάλε Βασίλη Παπακωνσταντίνου στο spotify"))
        assertEquals(Command.PlayMusic("χατζιδάκι", null), parse("βάλε μου λίγο Χατζιδάκι"))
        assertEquals(Command.PlayMusic("ζωή αγαπημένη", MusicApp.YOUTUBE_MUSIC), parse("θέλω να ακούσω ζωή αγαπημένη από το youtube music"))
        assertEquals(Command.PlayMusic("μάλαμα", null, MusicKind.ARTIST), parse("παίξε τραγούδια του Μάλαμα"))
        assertEquals(Command.OpenApp("Spotify"), parse("παίξε spotify"))
    }

    @Test
    fun musicKinds() {
        assertEquals(
            Command.PlayMusic("γυμναστική", MusicApp.SPOTIFY, MusicKind.PLAYLIST),
            parse("βάλε τη λίστα μου γυμναστική στο Spotify"),
        )
        assertEquals(Command.PlayMusic("ταξίδι", null, MusicKind.PLAYLIST), parse("παίξε την playlist ταξίδι"))
        assertEquals(Command.PlayMusic("ταξίδι", null, MusicKind.PLAYLIST), parse("βάλε τη ταξίδι playlist"))
        assertEquals(
            Command.PlayMusic("fear of the dark", MusicApp.SPOTIFY, MusicKind.SONG, "iron maiden"),
            parse("παίξε το τραγούδι Fear of the Dark των Iron Maiden στο spotify"),
        )
        assertEquals(Command.PlayMusic("powerslave", null, MusicKind.ALBUM), parse("παίξε το άλμπουμ Powerslave"))
        assertEquals(Command.PlayMusic("τα αγαπημένα μου", null, MusicKind.LIKED), parse("βάλε τα αγαπημένα μου"))
    }

    @Test
    fun englishWithGreekNamesStillWorks() {
        assertEquals(Command.PlayMusic("βασίλης παπακωνσταντίνου", MusicApp.SPOTIFY), parse("play Βασίλης Παπακωνσταντίνου on spotify"))
    }

    @Test
    fun weather() {
        assertEquals(Command.Weather(null, WeatherDay.NOW), parse("καιρός"))
        assertEquals(Command.Weather(null, WeatherDay.TOMORROW), parse("καιρός αύριο"))
        assertEquals(Command.Weather("αθήνα", WeatherDay.TOMORROW), parse("τι καιρό θα κάνει αύριο στην Αθήνα;"))
        assertEquals(Command.Weather("θεσσαλονίκη", WeatherDay.NOW), parse("πώς είναι ο καιρός στη Θεσσαλονίκη"))
        assertEquals(Command.Weather("νέα υόρκη", WeatherDay.WEEK), parse("καιρός στη Νέα Υόρκη αυτή την εβδομάδα"))
        assertEquals(Command.Weather("ηράκλειο", WeatherDay.NOW), parse("Ηράκλειο καιρός"))
        assertEquals(Command.Weather(null, WeatherDay.TOMORROW), parse("θα βρέξει αύριο;"))
        assertEquals(Command.Weather(null, WeatherDay.NOW), parse("πόσους βαθμούς έχει έξω"))
        assertEquals(Command.Weather(null, WeatherDay.NOW), parse("καιρός στην περιοχή μου"))
        assertNull(parse("κακός καιρός"))
    }

    @Test
    fun clock() {
        assertEquals(Command.Clock(null, null), parse("τι ώρα είναι;"))
        assertEquals(Command.Clock(null, null), parse("τι ημερομηνία έχουμε σήμερα"))
        val tokyo = expect<Command.Clock>("ώρα στο Τόκιο")
        assertEquals(ZoneId.of("Asia/Tokyo"), tokyo.zone)
        assertEquals("Τόκιο", tokyo.place)
        val ny = expect<Command.Clock>("τι ώρα είναι στη Νέα Υόρκη")
        assertEquals(ZoneId.of("America/New_York"), ny.zone)
        assertEquals("Νέα Υόρκη", ny.place)
        assertEquals(ZoneId.of("Europe/London"), expect<Command.Clock>("τι ώρα είναι στο London").zone)
    }

    @Test
    fun peopleAndPlaces() {
        assertEquals(Command.Call("μαμά"), parse("πάρε τη μαμά"))
        assertEquals(Command.Call("γιώργο"), parse("πάρε τηλέφωνο τον Γιώργο"))
        assertEquals(Command.Call("μαρία"), parse("τηλεφώνησε στη Μαρία"))
        assertEquals(Command.DialNumber("6912345678"), parse("κάλεσε 6912345678"))
        assertEquals(Command.Message("γιώργο", "έρχομαι σε 10 λεπτά"), parse("στείλε μήνυμα στον Γιώργο ότι έρχομαι σε 10 λεπτά"))
        assertEquals(Command.Message("μαμά", "αργώ λίγο"), parse("στείλε στη μαμά ότι αργώ λίγο"))
        assertEquals(Command.Message("νίκο", null), parse("στείλε μήνυμα στον Νίκο"))
        assertNull(parse("στείλε στη μαμά"))
        assertEquals(Command.Navigate("αεροδρόμιο"), parse("πήγαινέ με στο αεροδρόμιο"))
        assertEquals(Command.Navigate("ακρόπολη"), parse("οδηγίες για την Ακρόπολη"))
        assertEquals(Command.Navigate("σύνταγμα"), parse("πώς θα πάω στο Σύνταγμα"))
        assertEquals(Command.Camera(video = false), parse("βγάλε μια φωτογραφία"))
        assertEquals(Command.Camera(video = true), parse("τράβα βίντεο"))
    }

    @Test
    fun settingsPanels() {
        assertEquals(Command.OpenPanel(SettingsPanel.WIFI, Switch.ON), parse("άνοιξε το wifi"))
        assertEquals(Command.OpenPanel(SettingsPanel.BLUETOOTH, Switch.OFF), parse("κλείσε το bluetooth"))
        assertEquals(Command.OpenPanel(SettingsPanel.AIRPLANE, null), parse("λειτουργία πτήσης"))
        assertEquals(Command.OpenPanel(SettingsPanel.DND, Switch.ON), parse("ενεργοποίησε το μην ενοχλείτε"))
        assertEquals(Command.OpenPanel(SettingsPanel.INTERNET, Switch.OFF), parse("κλείσε τα δεδομένα"))
        assertNull(parse("δεδομένα"))
    }

    @Test
    fun appsAndLookups() {
        assertEquals(Command.OpenApp("viber"), parse("άνοιξε το viber"))
        assertEquals(Command.OpenApp("ρυθμίσεις"), parse("άνοιξε τις ρυθμίσεις"))
        assertEquals(Command.Lookup("κβάζαρ"), parse("τι είναι κβάζαρ"))
        assertEquals(Command.Lookup("νίκος καζαντζάκης"), parse("ποιος ήταν ο Νίκος Καζαντζάκης;"))
        assertEquals(Command.Lookup("λισαβόνα"), parse("πες μου για τη Λισαβόνα"))
        assertEquals(Command.Lookup("μελαγχολία"), parse("τι σημαίνει μελαγχολία"))
        assertNull(parse("τι είναι αυτό"))
    }

    @Test
    fun conversions() {
        val km = expect<Command.Convert>("5 χιλιόμετρα σε μίλια").conversion
        assertEquals("km", km.from.symbol)
        assertEquals("mi", km.to.symbol)
        assertEquals(5.0, km.amount, 0.0)
        assertEquals("mi", expect<Command.Convert>("5 χλμ σε μίλια").conversion.to.symbol)
        val pounds = expect<Command.Convert>("πόσα κιλά είναι 10 λίβρες").conversion
        assertEquals("lb", pounds.from.symbol)
        assertEquals("kg", pounds.to.symbol)
        assertEquals(10.0, pounds.amount, 0.0)
        assertEquals("°C", expect<Command.Convert>("100 βαθμοί φαρενάιτ σε κελσίου").conversion.to.symbol)
        assertEquals(2.5, expect<Command.Convert>("2,5 λίτρα σε γαλόνια").conversion.amount, 0.0)
    }

    @Test
    fun arithmetic() {
        assertEquals("12 * 7", GreekCommandParser.arithmetic("πόσο κάνει 12 επί 7"))
        assertEquals("100 / 4", GreekCommandParser.arithmetic("100 διά 4"))
        assertEquals("5 + 3", GreekCommandParser.arithmetic("πέντε συν τρία"))
        assertNull(GreekCommandParser.arithmetic("πόσο κάνει το ψωμί"))
        assertNull(GreekCommandParser.arithmetic("12 * 7"))
    }

    @Test
    fun ordinarySearchesStaySearches() {
        listOf("καφετέριες κοντά μου", "συνταγή για μουσακά", "Ολυμπιακός", "νέα iphone", "ταινίες").forEach {
            assertNull("'$it' should stay a search", parse(it))
        }
    }

    @Test
    fun accentsAndCaseDontMatter() {
        assertEquals(parse("βάλε ξυπνητήρι στις 7 το πρωί"), parse("ΒΑΛΕ ΞΥΠΝΗΤΗΡΙ ΣΤΙΣ 7 ΤΟ ΠΡΩΙ"))
        assertEquals(parse("χρονόμετρο 10 λεπτά"), parse("χρονομετρο 10 λεπτα"))
    }
}
