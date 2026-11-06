package com.weathercurrency.app.data

/**
 * Maps WMO weather interpretation codes (Open-Meteo) to Russian descriptions
 * and a stable emoji icon used by the UI.
 */
object WeatherCodes {

    data class Info(val description: String, val icon: String)

    fun describe(code: Int): Info = when (code) {
        0 -> Info("Ясно", "☀️")
        1 -> Info("Малооблачно", "🌤️")
        2 -> Info("Переменная облачность", "⛅")
        3 -> Info("Облачно", "☁️")
        45, 48 -> Info("Туман", "🌫️")
        in 51..57 -> Info("Морось", "🌦️")
        in 61..67 -> Info("Дождь", "🌧️")
        in 71..77 -> Info("Снег", "🌨️")
        80, 81, 82 -> Info("Ливень", "🌧️")
        85, 86 -> Info("Снегопад", "❄️")
        in 95..99 -> Info("Гроза", "⛈️")
        else -> Info("Неизвестно", "🌡️")
    }
}
