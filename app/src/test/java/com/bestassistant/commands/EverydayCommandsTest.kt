package com.bestassistant.commands

import com.bestassistant.integrations.homeassistant.HomeAssistantIntegration
import com.bestassistant.integrations.homeassistant.HomeRequests
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Battery, alarm controls, dates, chance, money, words, the calendar, phone settings, routines and calls. */
class EverydayCommandsTest {

    /** Saturday 17 Oct 2026, 08:15 in the morning. */
    private val morning = LocalDateTime.of(2026, 10, 17, 8, 15)
    private val today = morning.toLocalDate()

    private fun parse(text: String, now: LocalDateTime = morning) = CommandParser.parse(text, now)

    /** With Home Assistant installed, so none of these end up as smart-home requests. */
    @Before
    fun installHomeAssistant() {
        CommandParser.integrations = listOf(CommandParser.Installed(HomeAssistantIntegration.ID, HomeRequests))
    }

    @After
    fun uninstall() {
        CommandParser.integrations = emptyList()
        CommandParser.routines = emptyList()
    }

    @Test
    fun battery() {
        assertEquals(Command.Battery(), parse("how much battery do I have"))
        assertEquals(Command.Battery(), parse("how much battery is left"))
        assertEquals(Command.Battery(), parse("what's my battery level"))
        assertEquals(Command.Battery(), parse("battery"))
        assertEquals(Command.Battery(charging = true), parse("is my phone charging?"))
        assertEquals(Command.Battery(charging = true), parse("how long until my phone is fully charged"))
        assertEquals(Command.OpenPanel(SettingsPanel.BATTERY, null), parse("battery saver"))
    }

    @Test
    fun alarmAndTimerControls() {
        assertEquals(Command.DismissAlarm(AlarmTarget.At(7, 0, false)), parse("cancel my 7am alarm"))
        assertEquals(Command.DismissAlarm(AlarmTarget.At(19, 30, true)), parse("turn off the 7:30 pm alarm"))
        assertEquals(Command.DismissAlarm(AlarmTarget.At(6, 0, null)), parse("delete my 6 o'clock alarm"))
        assertEquals(Command.DismissAlarm(AlarmTarget.All), parse("cancel all alarms"))
        assertEquals(Command.DismissAlarm(AlarmTarget.All), parse("delete all my alarms"))
        assertEquals(Command.DismissAlarm(AlarmTarget.Next), parse("cancel my next alarm"))
        assertEquals(Command.DismissAlarm(AlarmTarget.Next), parse("cancel tomorrow's alarm"))
        assertEquals(Command.DismissAlarm(AlarmTarget.Ringing), parse("stop the alarm"))
        assertEquals(Command.DismissAlarm(AlarmTarget.Ringing), parse("turn off the alarm"))
        assertEquals(Command.DismissAlarm(AlarmTarget.Labeled("gym")), parse("cancel my gym alarm"))
        assertEquals(Command.SnoozeAlarm(), parse("snooze"))
        assertEquals(Command.SnoozeAlarm(10), parse("snooze for 10 minutes"))
        assertEquals(Command.SnoozeAlarm(5), parse("snooze five minutes"))
        assertEquals(Command.StopTimer, parse("stop the timer"))
        assertEquals(Command.StopTimer, parse("cancel my timer"))
        // Setting them works as before.
        assertEquals(Command.SetAlarm(7, 0), parse("set an alarm for 7am"))
        assertEquals(Command.SetTimer(300), parse("timer 5 min"))
    }

    @Test
    fun daysUntil() {
        assertEquals(Command.DaysUntil(LocalDate.of(2026, 12, 25), "Christmas"), parse("how many days until Christmas"))
        assertEquals(Command.DaysUntil(LocalDate.of(2026, 12, 25), "Christmas"), parse("how long till xmas"))
        assertEquals(Command.DaysUntil(LocalDate.of(2026, 12, 25), "Christmas", weeks = true), parse("how many weeks until christmas"))
        assertEquals(Command.DaysUntil(LocalDate.of(2027, 3, 3), null), parse("how many days until March 3rd"))
        assertEquals(Command.DaysUntil(LocalDate.of(2026, 10, 23), null), parse("how many days until friday"))
        assertEquals(Command.DaysUntil(LocalDate.of(2026, 1, 1), "New Year's Day", since = true), parse("how many days since new year's day"))
        // Easter is Orthodox Easter, as in the name days.
        assertEquals(Command.DaysUntil(LocalDate.of(2027, 5, 2), "Easter"), parse("when is easter"))
        assertEquals(Command.DaysUntil(LocalDate.of(2027, 3, 28), "Catholic Easter"), parse("when is catholic easter"))
        assertEquals(Command.DaysUntil(LocalDate.of(2027, 5, 21), "Eleni's name day"), parse("how many days until eleni's name day"))
        // The name days keep their own question.
        assertEquals(Command.NameDay("Eleni"), parse("when is eleni's name day"))
    }

    @Test
    fun whatDay() {
        assertEquals(Command.WhatDay(LocalDate.of(2027, 3, 3), null), parse("what day is March 3rd"))
        assertEquals(Command.WhatDay(LocalDate.of(2027, 3, 3), null), parse("what day of the week is the third of march"))
        assertEquals(Command.WhatDay(LocalDate.of(2026, 10, 23), null), parse("what day is the twenty-third of october"))
        assertEquals(Command.WhatDay(LocalDate.of(2026, 12, 25), "Christmas"), parse("what day does christmas fall on"))
        assertEquals(Command.WhatDay(LocalDate.of(2026, 11, 7), null), parse("what's the date in 3 weeks"))
        assertEquals(Command.WhatDay(LocalDate.of(2030, 7, 4), null), parse("what day is july 4th 2030"))
        // Today's date is the clock's.
        assertEquals(Command.Clock(null, null), parse("what day is it"))
    }

    @Test
    fun easterDates() {
        assertEquals(LocalDate.of(2026, 4, 5), Dates.westernEaster(2026))
        assertEquals(LocalDate.of(2027, 3, 28), Dates.westernEaster(2027))
        assertEquals(LocalDate.of(2025, 4, 20), Dates.westernEaster(2025))
    }

    @Test
    fun chance() {
        assertEquals(Command.Chance(ChanceKind.Coin), parse("flip a coin"))
        assertEquals(Command.Chance(ChanceKind.Coin), parse("heads or tails"))
        assertEquals(Command.Chance(ChanceKind.Dice()), parse("roll a dice"))
        assertEquals(Command.Chance(ChanceKind.Dice()), parse("roll the die"))
        assertEquals(Command.Chance(ChanceKind.Dice(2, 6)), parse("roll two dice"))
        assertEquals(Command.Chance(ChanceKind.Dice(1, 20)), parse("roll a d20"))
        assertEquals(Command.Chance(ChanceKind.Dice(1, 12)), parse("roll a twelve-sided die"))
        assertEquals(Command.Chance(ChanceKind.Dice(2, 6)), parse("roll 2d6"))
        assertEquals(Command.Chance(ChanceKind.Number(1, 10)), parse("pick a number from 1 to 10"))
        assertEquals(Command.Chance(ChanceKind.Number(1, 100)), parse("pick a random number between one and a hundred"))
        assertEquals(Command.Chance(ChanceKind.Number(1, 10)), parse("pick a number"))
        assertEquals(Command.Chance(ChanceKind.Number(1, 100)), parse("random number"))
        assertEquals(Command.Chance(ChanceKind.Number(5, 50)), parse("random number between 50 and 5"))
    }

    @Test
    fun currencies() {
        assertEquals(Command.Currency(100.0, "USD", "EUR"), parse("100 dollars in euros"))
        assertEquals(Command.Currency(100.0, "USD", "EUR"), parse("convert $100 to euros"))
        assertEquals(Command.Currency(50.0, "EUR", "GBP"), parse("50€ to £"))
        assertEquals(Command.Currency(1.0, "EUR", "JPY"), parse("how many yen in a euro"))
        assertEquals(Command.Currency(20.0, "GBP", "USD"), parse("how many dollars is 20 pounds"))
        assertEquals(Command.Currency(1.0, "EUR", "USD"), parse("euro to dollar exchange rate"))
        assertEquals(Command.Currency(1.0, "USD", "EUR"), parse("what's the exchange rate from dollars to euros"))
        assertEquals(Command.Currency(2000.0, "CHF", "EUR"), parse("two thousand swiss francs in euros"))
        assertEquals(Command.Currency(1500.0, "EUR", "TRY"), parse("1,500 euros to turkish lira"))
        assertEquals(Command.Currency(2.5, "EUR", "USD"), parse("2.50 eur in usd"))
        // Pounds of weight stay weight.
        assertTrue(parse("10 pounds in kg") is Command.Convert)
        assertTrue(parse("how many ounces in a pound") is Command.Convert)
        assertTrue(parse("how to say hello in french") !is Command.Currency)
    }

    @Test
    fun words() {
        assertEquals(Command.Define("serendipity"), parse("define serendipity"))
        assertEquals(Command.Define("serendipity"), parse("what does serendipity mean"))
        assertEquals(Command.Define("ubiquitous"), parse("what's the meaning of ubiquitous"))
        assertEquals(Command.Define("ephemeral"), parse("ephemeral definition"))
        assertEquals(Command.Spell("necessary"), parse("how do you spell necessary"))
        assertEquals(Command.Spell("rhythm"), parse("spell rhythm"))
        assertEquals(Command.Spell("accommodate"), parse("how is accommodate spelled"))
        // Longer phrases are still topics for Wikipedia.
        assertEquals(Command.Lookup("history of the roman empire"), parse("define the history of the roman empire"))
        assertEquals(Command.Lookup("quasar"), parse("what is a quasar"))
    }

    @Test
    fun readingTheCalendar() {
        assertEquals(Command.Agenda(today), parse("what's on my calendar today"))
        assertEquals(Command.Agenda(today), parse("what's on my calendar"))
        assertEquals(Command.Agenda(today.plusDays(1)), parse("what do I have tomorrow"))
        assertEquals(Command.Agenda(LocalDate.of(2026, 10, 23)), parse("what's on my schedule for friday"))
        assertEquals(Command.Agenda(LocalDate.of(2026, 10, 23)), parse("do i have anything on friday"))
        assertEquals(Command.Agenda(today), parse("what does my day look like"))
        assertEquals(Command.Agenda(today), parse("do I have any meetings today"))
        assertEquals(Command.Agenda(today, 2), parse("what's on my calendar this weekend"))
        assertEquals(Command.Agenda(LocalDate.of(2026, 10, 19), 7), parse("what's on my calendar next week"))
        assertEquals(Command.Agenda(LocalDate.of(2026, 10, 20), 6), parse("what's on my calendar this week", LocalDateTime.of(2026, 10, 20, 9, 0)))
        assertEquals(Command.NextEvent, parse("what's my next meeting"))
        assertEquals(Command.NextEvent, parse("when is my next appointment"))
    }

    @Test
    fun addingEvents() {
        assertEquals(Command.AddEvent("Dentist", EventTime.At(today.plusDays(1), 15, 0)), parse("add dentist tomorrow at 3"))
        assertEquals(
            Command.AddEvent("Meeting with alex", EventTime.At(LocalDate.of(2026, 10, 23), 10, 0)),
            parse("add a meeting with alex on friday at 10 to my calendar"),
        )
        assertEquals(Command.AddEvent("Lunch with mom", EventTime.At(today, 13, 0), 90), parse("schedule lunch with mom at 1 for an hour and a half"))
        assertEquals(
            Command.AddEvent("Mom's birthday", EventTime.AllDay(LocalDate.of(2026, 11, 3))),
            parse("put mom's birthday on my calendar on november 3rd"),
        )
        assertEquals(Command.AddEvent("Team lunch", EventTime.At(today.plusDays(1), 12, 0)), parse("create an event called team lunch tomorrow at noon"))
        assertEquals(Command.AddEvent(null, null), parse("create an event"))
        assertEquals(Command.AddEvent("Meeting", null), parse("schedule a meeting"))
        // Not calendar events.
        assertTrue(parse("book a table for 4 at 8") !is Command.AddEvent)
        assertTrue(parse("add milk to my shopping list") !is Command.AddEvent)
        assertTrue(parse("add this song to my liked songs") !is Command.AddEvent)
        assertTrue(parse("put on some iron maiden") is Command.PlayMusic)
        assertTrue(parse("put the heating on at 6") !is Command.AddEvent)
    }

    @Test
    fun remindersOnADate() {
        val remind = parse("remind me on march 3rd to call the bank") as Command.Remind
        assertEquals("call the bank", remind.what)
        assertEquals(ReminderTime.At(LocalDate.of(2027, 3, 3), 9, 0), remind.time)
        val christmas = parse("remind me on christmas eve at 6 pm to call grandma") as Command.Remind
        assertEquals("call grandma", christmas.what)
        assertEquals(ReminderTime.At(LocalDate.of(2026, 12, 24), 18, 0), christmas.time)
        // A holiday's name without "on" is just words.
        assertEquals("buy easter eggs", (parse("remind me tomorrow to buy easter eggs") as Command.Remind).what)
    }

    @Test
    fun brightnessAndRinger() {
        assertEquals(Command.Brightness(BrightnessChange.Set(50)), parse("brightness 50%"))
        assertEquals(Command.Brightness(BrightnessChange.Set(30)), parse("set the brightness to thirty percent"))
        assertEquals(Command.Brightness(BrightnessChange.Set(100)), parse("max brightness"))
        assertEquals(Command.Brightness(BrightnessChange.Up), parse("make the screen brighter"))
        assertEquals(Command.Brightness(BrightnessChange.Down), parse("dim the screen"))
        assertEquals(Command.Brightness(BrightnessChange.Auto(true)), parse("turn on adaptive brightness"))
        assertEquals(Command.Brightness(BrightnessChange.Auto(false)), parse("auto-brightness off"))
        assertEquals(Command.OpenPanel(SettingsPanel.DISPLAY, null), parse("brightness"))
        assertEquals(Command.Ringer(RingerMode.SILENT), parse("silent mode"))
        assertEquals(Command.Ringer(RingerMode.SILENT), parse("put my phone on silent"))
        assertEquals(Command.Ringer(RingerMode.SILENT), parse("mute my phone"))
        assertEquals(Command.Ringer(RingerMode.VIBRATE), parse("vibrate"))
        assertEquals(Command.Ringer(RingerMode.VIBRATE), parse("put the phone on vibrate"))
        assertEquals(Command.Ringer(RingerMode.NORMAL), parse("turn off silent mode"))
        assertEquals(Command.Ringer(RingerMode.NORMAL), parse("ring mode"))
        assertEquals(Command.OpenPanel(SettingsPanel.DND, Switch.ON), parse("turn on do not disturb"))
        // "Mute" alone is still the music.
        assertEquals(Command.Volume(VolumeChange.Mute), parse("mute"))
        // The house's lights are Home Assistant's.
        assertTrue(parse("set the kitchen light brightness to 50") is Command.Integration)
    }

    @Test
    fun missedCalls() {
        assertEquals(Command.MissedCalls, parse("who called me"))
        assertEquals(Command.MissedCalls, parse("did I miss any calls?"))
        assertEquals(Command.MissedCalls, parse("any missed calls"))
        assertEquals(Command.CallBack(), parse("call back"))
        assertEquals(Command.CallBack(), parse("call them back"))
        assertEquals(Command.CallBack(), parse("return the call"))
        assertEquals(Command.CallBack(redial = true), parse("redial"))
        assertEquals(Command.CallBack(redial = true), parse("call the last number again"))
        assertEquals(Command.Call("mom"), parse("call mom back"))
    }

    @Test
    fun routines() {
        CommandParser.routines = listOf(CommandParser.RoutineTrigger("Good morning", setOf("good morning", "morning routine")))
        assertEquals(Command.Routine("Good morning"), parse("good morning"))
        assertEquals(Command.Routine("Good morning"), parse("Hey Pocket, good morning!"))
        assertEquals(Command.Routine("Good morning"), parse("morning routine"))
        CommandParser.routines = emptyList()
        assertTrue(parse("good morning") !is Command.Routine)
    }

    @Test
    fun informationIsNotRunOnItsOwn() {
        for (command in listOf(parse("battery"), parse("define serendipity"), parse("who called me"), parse("100 dollars in euros"))) {
            assertTrue("$command", command!!.isInformation && !command.isSelfContained)
        }
        for (command in listOf(parse("flip a coin"), parse("silent mode"), parse("add dentist tomorrow at 3"), parse("call back"))) {
            assertTrue("$command", command!!.isSelfContained)
        }
    }
}
