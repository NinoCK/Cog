package com.bestassistant.commands

import java.util.Locale

/**
 * Money as people say it: "100 dollars in euros", "$20 to £", "how many yen is a euro", "euro to
 * dollar exchange rate". Only the currencies the European Central Bank publishes a reference rate
 * for, by their ISO code.
 */
internal object Currencies {

    /** Each ISO code, with how to say one and many of it. */
    val names: Map<String, Pair<String, String>> = mapOf(
        "EUR" to ("euro" to "euros"), "USD" to ("US dollar" to "US dollars"), "GBP" to ("British pound" to "British pounds"),
        "JPY" to ("Japanese yen" to "Japanese yen"), "CHF" to ("Swiss franc" to "Swiss francs"),
        "CAD" to ("Canadian dollar" to "Canadian dollars"), "AUD" to ("Australian dollar" to "Australian dollars"),
        "NZD" to ("New Zealand dollar" to "New Zealand dollars"), "HKD" to ("Hong Kong dollar" to "Hong Kong dollars"),
        "SGD" to ("Singapore dollar" to "Singapore dollars"), "CNY" to ("Chinese yuan" to "Chinese yuan"),
        "INR" to ("Indian rupee" to "Indian rupees"), "KRW" to ("South Korean won" to "South Korean won"),
        "SEK" to ("Swedish krona" to "Swedish kronor"), "NOK" to ("Norwegian krone" to "Norwegian kroner"),
        "DKK" to ("Danish krone" to "Danish kroner"), "PLN" to ("Polish zloty" to "Polish zloty"),
        "CZK" to ("Czech koruna" to "Czech korunas"), "HUF" to ("Hungarian forint" to "Hungarian forints"),
        "RON" to ("Romanian leu" to "Romanian lei"), "TRY" to ("Turkish lira" to "Turkish lira"),
        "ISK" to ("Icelandic krona" to "Icelandic kronur"), "BRL" to ("Brazilian real" to "Brazilian reais"),
        "MXN" to ("Mexican peso" to "Mexican pesos"), "ZAR" to ("South African rand" to "South African rand"),
        "ILS" to ("Israeli shekel" to "Israeli shekels"), "MYR" to ("Malaysian ringgit" to "Malaysian ringgit"),
        "THB" to ("Thai baht" to "Thai baht"), "IDR" to ("Indonesian rupiah" to "Indonesian rupiah"),
        "PHP" to ("Philippine peso" to "Philippine pesos"),
    )

    private val aliases: Map<String, String> = buildMap {
        fun add(code: String, vararg words: String) {
            for (word in words) put(word, code)
            put(code.lowercase(Locale.ROOT), code)
        }
        add("EUR", "euro", "euros", "€", "eur")
        add("USD", "dollar", "dollars", "us dollar", "us dollars", "u.s. dollars", "american dollar", "american dollars", "buck", "bucks", "$", "usd")
        add("GBP", "pound", "pounds", "british pound", "british pounds", "pound sterling", "pounds sterling", "sterling", "quid", "uk pounds", "£")
        add("JPY", "yen", "japanese yen", "¥")
        add("CHF", "franc", "francs", "swiss franc", "swiss francs")
        add("CAD", "canadian dollar", "canadian dollars")
        add("AUD", "australian dollar", "australian dollars", "aussie dollar", "aussie dollars")
        add("NZD", "new zealand dollar", "new zealand dollars", "kiwi dollar", "kiwi dollars")
        add("HKD", "hong kong dollar", "hong kong dollars")
        add("SGD", "singapore dollar", "singapore dollars")
        add("CNY", "yuan", "chinese yuan", "renminbi", "rmb")
        add("INR", "rupee", "rupees", "indian rupee", "indian rupees")
        add("KRW", "won", "korean won", "south korean won")
        add("SEK", "swedish krona", "swedish kronor", "swedish crown", "swedish crowns", "kronor")
        add("NOK", "norwegian krone", "norwegian kroner", "norwegian crown", "norwegian crowns")
        add("DKK", "danish krone", "danish kroner", "danish crown", "danish crowns")
        add("PLN", "zloty", "zlotys", "zlotych", "polish zloty", "polish zlotys")
        add("CZK", "koruna", "korunas", "czech koruna", "czech korunas", "czech crown", "czech crowns")
        add("HUF", "forint", "forints", "hungarian forint", "hungarian forints")
        add("RON", "leu", "lei", "romanian leu", "romanian lei")
        add("TRY", "lira", "liras", "turkish lira", "turkish liras")
        add("ISK", "icelandic krona", "icelandic kronur")
        add("BRL", "real", "reais", "brazilian real", "brazilian reais")
        add("MXN", "peso", "pesos", "mexican peso", "mexican pesos")
        add("ZAR", "rand", "south african rand")
        add("ILS", "shekel", "shekels", "israeli shekel", "israeli shekels", "new shekel", "new shekels", "nis")
        add("MYR", "ringgit", "malaysian ringgit")
        add("THB", "baht", "thai baht")
        add("IDR", "rupiah", "indonesian rupiah")
        add("PHP", "philippine peso", "philippine pesos")
    }

    /** The ISO code for what the user called a currency, or null. */
    fun find(raw: String): String? {
        val text = raw.trim().removePrefix("the ").removePrefix("a ").removePrefix("an ").removePrefix("one ").trim()
        return aliases[text]
    }

    private const val AMOUNT = "(\\d+(?:[.,]\\d+)*|a|an|one)"
    private const val SYMBOL = "([$€£¥])"

    /** "100 dollars to euros", "$100 in euros", "100$ to €", "dollars to euros". */
    private val forward = Regex("^(?:convert |change |what is |what's |how much is |how much are |how much would )?(?:$SYMBOL ?)?(?:$AMOUNT ?)?(?:$SYMBOL )?(.*?) ?(?:to|in|into|in to|=|->|→|is how many|are how many|be in) (.+?)(?: be)?$")

    /** "how many euros is 100 dollars", "how many yen in a euro". */
    private val howMany = Regex("^how (?:many|much) (.+?) (?:is|are|in|for|per|to|would i get for|do i get for|is there in) (?:$SYMBOL ?)?(?:$AMOUNT ?)?(.+)$")

    /** "euro to dollar exchange rate", "exchange rate from dollars to euros", "what's the dollar rate in euros". */
    private val rate = Regex("^(?:what(?:'s| is) )?(?:the )?(?:current )?(?:exchange|conversion) rate (?:of |for |from |between )?(.+?) (?:to|and|in|into|vs|versus|against) (.+)$")
    private val rateAfter = Regex("^(?:what(?:'s| is) )?(?:the )?(.+?) (?:to |in |and |vs )?(\\S+) (?:exchange |conversion )?rate$")

    /** "a hundred", "20 thousand", "1.5 million": the number written out. */
    private val scaled = Regex("(\\d+(?:[.,]\\d+)?|a|an|one) (hundred|thousand|million|k)(?![a-z])")

    fun parse(raw: String): Command.Currency? {
        val text = Spoken.digitize(raw).replace(scaled) { m ->
            val base = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 1.0
            val factor = when (m.groupValues[2]) {
                "hundred" -> 100.0
                "thousand", "k" -> 1_000.0
                else -> 1_000_000.0
            }
            format(base * factor)
        }.replace(Regex("(\\d)([$€£¥])"), "$1 $2")
        // Each reading in turn: "how many yen in a euro" also reads as "<how many yen> in <a euro>".
        forward.matchEntire(text)?.let { m ->
            val symbol = m.groupValues[1].ifEmpty { m.groupValues[3] }
            val fromText = m.groupValues[4].ifEmpty { symbol }
            val sameCurrency = symbol.isEmpty() || m.groupValues[4].isEmpty() || find(symbol) == find(m.groupValues[4])
            val amount = amount(m.groupValues[2])
            if (sameCurrency && amount != null) build(amount, fromText, m.groupValues[5])?.let { return it }
        }
        howMany.matchEntire(text)?.let { m ->
            val fromText = m.groupValues[4].ifEmpty { m.groupValues[2] }
            amount(m.groupValues[3])?.let { amount -> build(amount, fromText, m.groupValues[1])?.let { return it } }
        }
        return (rate.matchEntire(text) ?: rateAfter.matchEntire(text))?.let { m -> build(1.0, m.groupValues[1], m.groupValues[2]) }
    }

    private fun build(amount: Double, fromText: String, toText: String): Command.Currency? {
        val from = find(fromText) ?: return null
        val to = find(toText) ?: return null
        if (from == to || amount <= 0 || amount > MAX_AMOUNT) return null
        return Command.Currency(amount, from, to)
    }

    /** "100", "1,000" (grouping), "2,50" (a decimal comma), "a". Missing: one. */
    private fun amount(text: String): Double? = when {
        text.isEmpty() || text in setOf("a", "an", "one") -> 1.0
        Regex("^\\d{1,3}(?:,\\d{3})+$").matches(text) -> text.replace(",", "").toDoubleOrNull()
        else -> text.replace(',', '.').toDoubleOrNull()
    }

    private fun format(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    private const val MAX_AMOUNT = 1e12
}
