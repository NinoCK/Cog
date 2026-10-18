package com.bestassistant.device

import com.bestassistant.commands.Command
import com.fastsearch.FastSearchApp

/**
 * The spoken answer to an information request, looked up now: what the overlay shows in a card
 * (weather, Wikipedia, the calendar...), for when there's no card to read from, as in a routine.
 */
class Answers(private val app: FastSearchApp) {

    suspend fun answer(command: Command): String? {
        val responder = app.responder
        return when (command) {
            is Command.Weather -> weather(command)
            is Command.Lookup -> {
                val found = app.wikipedia.lookup(command.topic)
                val article = found.getOrNull()
                when {
                    found.isFailure -> responder.forArticleUnavailable()
                    article == null -> responder.forArticleMissing(command.topic)
                    else -> responder.forArticle(article)
                }
            }
            is Command.Currency -> {
                val rates = app.exchangeRates.rates().getOrNull() ?: return responder.forCurrencyUnavailable()
                val result = rates.convert(command.amount, command.from, command.to) ?: return responder.forCurrencyUnsupported(command)
                responder.forCurrency(command, result)
            }
            is Command.Define -> {
                val found = app.wiktionary.define(command.word)
                val definition = found.getOrNull()
                when {
                    found.isFailure -> responder.forDefinitionUnavailable()
                    definition == null -> responder.forDefinitionMissing(command.word)
                    else -> responder.forDefinition(definition)
                }
            }
            is Command.Agenda -> if (!app.calendar.canRead()) {
                responder.forCalendarNeedsAccess()
            } else {
                responder.forAgenda(command, app.calendar.events(command.from, command.days))
            }
            Command.NextEvent -> if (!app.calendar.canRead()) responder.forCalendarNeedsAccess() else responder.forNextEvent(app.calendar.next())
            Command.MissedCalls -> if (!app.calls.canRead()) {
                responder.forCallsNeedAccess()
            } else {
                responder.forMissedCalls(app.calls.missed(), app.calls.lastIncoming())
            }
            else -> responder.forCommand(command)
        }
    }

    private suspend fun weather(command: Command.Weather): String {
        val responder = app.responder
        val imperial = app.preferences.settings.value.units.isImperial()
        val place = command.place
        val forecast = if (place == null) {
            if (!app.location.hasPermission()) return responder.forWeatherNeedsLocation()
            val here = app.location.current() ?: return responder.forWeatherUnavailable()
            app.weather.forecast(here.latitude, here.longitude, null, imperial)
        } else {
            val found = app.weather.geocode(place)
            val geo = found.getOrNull() ?: return if (found.isFailure) responder.forWeatherUnavailable() else responder.forWeatherNotFound(place)
            app.weather.forecast(geo.latitude, geo.longitude, geo.name, imperial)
        }
        return forecast?.let { responder.forWeather(it, command.day) } ?: responder.forWeatherUnavailable()
    }
}
