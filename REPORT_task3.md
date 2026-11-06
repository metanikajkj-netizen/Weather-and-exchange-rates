# REPORT — TASK №3 — UI/UX POLISH «Погода и Валюта» v1.2

Исполнитель: MiMo Code
Дата: 2026-11-05
Проект: `/opt/weather-currency`
APK: `/opt/weather-currency/output/WeatherCurrency-v1.2.apk`

---

## 1. Найденные причины UI-проблем

### 1.1 Перекрытие заголовка статус-баром
- В `MainActivity` **не вызывался** `enableEdgeToEdge()`, а корневой `Column` в `MainScreen` **не имел** `windowInsetsPadding`.
- Контент рисовался под системной строкой состояния → заголовок «Погода и Валюта» обрезался.

### 1.2 Перекрытие нижней кнопки навигационной полосой
- Отсутствовал нижний inset: при edge-to-edge / прозрачной navigationBar последняя кнопка «Обновить» (карточка валют) уходила под gesture-bar или 3-кнопочную панель.
- Ранее не было Scaffold и не было компенсации safeDrawing → двойных отступов не было; проблема — полное **отсутствие** inset, а не двойной padding.

### 1.3 «Железнодорожный, 113»
- `CityResolver.format()` склеивала `locality` с `featureName`:
  `"$locality, $name"`, где `featureName` на Android часто = **номер дома** или улица («113»).
- Приоритет полей был неверным: `featureName` участвовал в представлении города.

### 1.4 «−0°»
- `String.format(Locale.US, "%+.0f°", -0.4)` даёт **«-0°»** (округление к нулю с сохранением знака).
- Форматирование было `private` внутри `MainScreen` — без unit-тестов.

## 2. Изменённые файлы

| Файл | Изменение |
|---|---|
| `app/build.gradle.kts` | `versionName=1.2`, `versionCode=3` |
| `MainActivity.kt` | `enableEdgeToEdge()` до `setContent` |
| `ui/MainScreen.kt` | `windowInsetsPadding(safeDrawing)` на корне; использование `Format.*`; компактные отступы; filled-кнопки; `TextOverflow` для длинных строк |
| `ui/Format.kt` | **новый** — `temp/wind/time/dayName` (чистые функции) |
| `location/PlaceLabel.kt` | **новый** — выбор названия населённого пункта |
| `location/CityResolver.kt` | только `locality/subAdminArea/adminArea`; fallback «Моё местоположение» |
| `data/WeatherRepository.kt` | убран `coordinatesLabel` для UI; GPS-координаты для API не меняются |
| `test/…/TemperatureFormatTest.kt` | **новый** — 12 тестов |
| `test/…/PlaceLabelTest.kt` | **новый** — 10 тестов |

**Не изменялись** (mtime задачи 2): `OpenMeteoClient.kt`, `CbrCurrencyClient.kt`, `ApiError.kt`, `CacheStore.kt`, `NetworkDiag.kt`, `LocationProvider.kt`, `MainViewModel.kt`, манифест, разрешения.

## 3. Реализованные исправления

### 3.1 Системные отступы
```kotlin
// MainActivity
enableEdgeToEdge()

// MainScreen — ровно один проход inset, без Scaffold (нет двойного padding)
Modifier
  .fillMaxSize()
  .windowInsetsPadding(WindowInsets.safeDrawing)  // status + nav + cutout
  .verticalScroll(rememberScrollState())
  .padding(horizontal = 16.dp, vertical = 12.dp)
```
- `safeDrawing` покрывает gesture и 3-кнопочную навигацию.
- Прокрутка: inset применяется к вьюпорту, контент скроллится внутри — нижняя кнопка не уходит под панель при доскролле.
- Дополнительный `Spacer(4.dp)` в конце колонки — страховка для крупных шрифтов.

### 3.2 Название местоположения
Приоритет `PlaceLabel.from()`:
1. `locality` (населённый пункт)
2. `subAdminArea` (район)
3. `adminArea` (регион/область)
4. `countryName`
5. иначе — **«Моё местоположение»** (`PlaceLabel.FALLBACK`)

- `featureName` / улица / номер дома **не используются** вообще.
- Чисто цифровые строки отбрасываются.
- `WeatherRepository` передаёт в погоду **те же** `lat/lon`; меняется только отображаемая подпись.

### 3.3 Отрицательный ноль
```kotlin
fun temp(celsius: Double): String {
    val rounded = /* round half away from zero */
    return when {
        rounded > 0 -> "+$rounded°"
        rounded < 0 -> "$rounded°"
        else -> "0°"   // −0.4, 0.0, +0.4, −0.0 → «0°»
    }
}
```
Единый формат для текущей температуры, «ощущается», прогноз min/max.

### 3.4 Дизайн (структура экрана сохранена)
- Material 3, светлая/тёмная темы — без изменений концепции.
- Отступы согласованы: карточки `padding(16)`, секции `8–12`, между карточками `12`.
- Кнопки «Обновить» — filled `Button` 48dp, full-width (заметнее `OutlinedButton`).
- Контраст: метки `onPrimaryContainer` α=0.85; ошибки — `colorScheme.error`.
- Длинный текст: `maxLines` + `TextOverflow.Ellipsis` (город, описание, курсы).
- Без новых анимаций и декора.
- Время загрузки валют — всегда при наличии снапшота (в т.ч. из кеша).

## 4. Результаты тестов

```bash
$ ./build.sh :app:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL
```

| Класс | Tests | Failures | Errors |
|---|---|---|---|
| WeatherParsingTest | 12 | 0 | 0 |
| CurrencyParsingTest | 13 | 0 | 0 |
| NetworkErrorTest | 14 | 0 | 0 |
| CacheSerializationTest | 5 | 0 | 0 |
| **TemperatureFormatTest** | **12** | 0 | 0 |
| **PlaceLabelTest** | **10** | 0 | 0 |
| **TOTAL** | **66** | **0** | **0** |

Обязательные кейсы температуры:

| Ввод | Ожидание |
|---|---|
| −0.4 | `0°` |
| 0.0 | `0°` |
| +0.4 | `0°` |
| −1.0 | `-1°` |
| +1.0 | `+1°` |

Плюс: −0.0, −0.5, NaN/Infinity, ветер, выбор города (locality → district → region → fallback), отказ от «113».

Регрессии сетевого слоя: прежние 44 теста (погода/валюты/ошибки/кеш) — **все зелёные**.

## 5. Результаты сборки

```
package: name='com.weathercurrency.app' versionCode='3' versionName='1.2'
minSdkVersion='26' targetSdkVersion='35'
application-label:'Погода и Валюта'
SIGNATURE_VALID
cert SHA-256: 90eaea49f52b8fd11c0cc70df69c88d2f1ba220614fcfbd6e35fc7c4fb42fe54  (= 1.0/1.1 → update-in-place)
permissions: INTERNET, ACCESS_NETWORK_STATE, ACCESS_COARSE/FINE_LOCATION (без изменений)
```

`ls -lh`:
```
-rw-r--r-- 1 root root 12M … /opt/weather-currency/output/WeatherCurrency-v1.2.apk
```

## 6. Ограничения проверки

1. **Визуальная проверка на реальном устройстве / эмуляторе для v1.2 НЕ выполнялась** (нет устройства/эмулятора на этой сборочной среде; эмулятор не запускался).
2. Insets (статус-бар, gesture/3-button), размер шрифта 1.3×, прокрутка на маленьком экране — проверены **на уровне кода и компиляции**, не скриншотом.
3. Геокодер зависит от ОС устройства; fallback «Моё местоположение» покрыт unit-тестами `PlaceLabel`.
4. APK — debug-сборка (как 1.0/1.1, по исходному заданию).

## 7. Путь и SHA-256 APK

| | |
|---|---|
| Путь | **`/opt/weather-currency/output/WeatherCurrency-v1.2.apk`** |
| На телефоне | `/sdcard/Download/WeatherCurrency-v1.2.apk` |
| SHA-256 | **`cf9b812fc7fa524dfb0a062e0c54885a3914adaf0db81465caeba4003acf3e55`** |
| Размер | **11 926 512** bytes |
| versionName / versionCode | **1.2 / 3** |
| SCP | `scp root@2.27.86.13:/opt/weather-currency/output/WeatherCurrency-v1.2.apk ~/storage/downloads/` |

## 8. Изоляция других проектов

```bash
$ find /opt/pump-tracker /root/pump-tracker /opt/vpnautoswitch-ru -xdev -mmin -120
(пусто)
```

Pump Tracker, VpnAutoSwitch и прочие директории **не изменялись**. Работа — только в `/opt/weather-currency`.

---

## ИТОГ (критерии готовности)

| # | Критерий | Статус |
|---|---|---|
| 1 | Заголовок не перекрывается статус-баром | код: `enableEdgeToEdge` + `safeDrawing` inset ✅ / на устройстве не смотрели |
| 2 | Нижняя кнопка не перекрывается nav-панелью | тот же inset + end-spacer ✅ / на устройстве не смотрели |
| 3 | Город без номера дома | `PlaceLabel` (только locality/district/region) ✅ unit |
| 4 | Нет «−0°» | `Format.temp` ✅ unit (−0.4…+1.0) |
| 5 | Интерфейс компактнее и аккуратнее | согласованные отступы, filled-кнопки, ellipsis ✅ |
| 6 | Погода и валюты сохранены | 44 сетевых теста без регрессий ✅ |
| 7 | APK 1.2 собран и подписан | **SUCCESSFUL**, SIGNATURE_VALID ✅ |

| | |
|---|---|
| BUILD | **SUCCESSFUL** |
| Тесты | **66 / 66 passed** |
| APK | `/opt/weather-currency/output/WeatherCurrency-v1.2.apk` |
| SHA-256 | `cf9b812fc7fa524dfb0a062e0c54885a3914adaf0db81465caeba4003acf3e55` |
| На реальном устройстве после сборки 1.2 | **не проверялось** |
