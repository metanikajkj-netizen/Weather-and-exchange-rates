# REPORT — TASK №5 — Переключатель ₽ / $ для криптовалют (v1.4)

Исполнитель: MiMo Code
Дата: 2026-11-05
Проект: `/opt/weather-currency`
APK: `/opt/weather-currency/output/WeatherCurrency-v1.4.apk`

---

## 1. Реализованные изменения

### 1.1 Переключатель в заголовке карточки криптовалют
- Компактный segmented control **«₽ | $»** справа от заголовка «Курсы криптовалют».
- Активная валюта выделена (`primary` / `onPrimary`), неактивная — приглушена.
- Тач-таргеты: `minWidth 44dp` × `minHeight 36dp` (компактно, но нажимаемо).
- Клик обрабатывается `clickable` на сегментах → **не конфликтует** с drag-свайпом `HorizontalPager` (pager реагирует только на drag, не на click).
- Строки монет: подпись **«в рублях» / «в долларах»** (`CryptoCurrency.label`).

### 1.2 Модель данных
```kotlin
enum class CryptoCurrency { RUB, USD }  // label: "в рублях" / "в долларах"

data class CryptoRate(
  id, symbol, name, icon,
  priceRub: Double? = null,   // может отсутствовать
  priceUsd: Double? = null    // может отсутствовать
) {
  fun priceFor(currency): Double?  // null → UI «—», без выдумывания
}
```

### 1.3 Источник — CoinGecko, одним запросом
```
GET https://api.coingecko.com/api/v3/simple/price
    ?ids=bitcoin,ethereum,solana
    &vs_currencies=rub,usd
```
- RUB и USD приходят **напрямую от CoinGecko**.
- **Не** пересчитываются через курс ЦБ РФ.
- API-ключей и платных сервисов нет.

**Live-проверка (HTTP 200):**
```json
{"bitcoin":{"rub":6605088,"usd":80546},
 "ethereum":{"rub":198707,"usd":2423.13},
 "solana":{"rub":7870.24,"usd":95.97}}
```

### 1.4 Поведение
| Действие | Эффект |
|---|---|
| Нажатие ₽ / $ | Мгновенное переключение **без сетевого запроса** (те же загруженные котировки) |
| По умолчанию | RUB |
| Выбор в DataStore | ключ `crypto_display_currency` → переживает перезапуск |
| «Обновить» / bootstrap | Один запрос **rub,usd** вместе |
| Офлайн | Кеш DataStore + «Последние сохранённые данные» |
| Отсутствует одна сторона | `null` → «—», **без** пересчёта из другой валюты |

### 1.5 Форматирование
| Валюта | Правило | Пример |
|---|---|---|
| RUB | прежнее (пробелы-тысячи; у ≥100k без копеек) | `6 605 088 ₽` |
| USD | пробел-тысячи, **запятая**-десятичные, 2 знака при ≥1 | `80 999,25 $`, `2 439,50 $`, `96,62 $` |
| невалидно / null | `—` | |

Спец. случаи USD: `80598.0` → `80 598,00 $`; `999.995` → `1 000,00 $` (carry).

### 1.6 Изменённые файлы
- `model/CryptoModels.kt` — enum + nullable цены  
- `data/CoinGeckoClient.kt` — `vs_currencies=rub,usd`, dual parse  
- `data/CacheStore.kt` — preference `crypto_display_currency`  
- `MainViewModel.kt` — `CryptoUiState.currency`, `setCryptoCurrency()` (persist, no API)  
- `ui/Format.kt` — `priceUsd`, `priceCrypto`  
- `ui/MainScreen.kt` — `RubUsdToggle`, `CryptoRow(currency)`, wiring  
- `MainActivity.kt` — callback  
- `app/build.gradle.kts` — versionName **1.4**, versionCode **5**  
- Тесты: `CryptoParsingTest`, `CryptoFormatTest`

**Не менялись:** погода, карточка обычных валют, пейджер, разрешения, сетевые классы погоды/ЦБ.

## 2. Тестирование

```bash
$ ./build.sh :app:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL in 1m 6s
```

| Класс | Tests | Failures | Errors |
|---|---|---|---|
| WeatherParsingTest | 12 | 0 | 0 |
| CurrencyParsingTest | 13 | 0 | 0 |
| NetworkErrorTest | 14 | 0 | 0 |
| CacheSerializationTest | 5 | 0 | 0 |
| TemperatureFormatTest | 12 | 0 | 0 |
| PlaceLabelTest | 10 | 0 | 0 |
| **CryptoParsingTest** | **11** | 0 | 0 |
| **CryptoFormatTest** | **14** | 0 | 0 |
| **TOTAL** | **91** | **0** | **0** |

Покрытие ТЗ:
- переключение RUB→USD→RUB на одной модели без API ✅ (`toggleDoesNotChangeDataModel`)  
- парсинг обеих валют, порядок BTC/ETH/SOL ✅  
- отсутствие USD не выводится из RUB (и наоборот) ✅  
- формат USD по примерам ТЗ + RUB ✅  
- `fromCode` / labels / default RUB ✅  
- 0/негативные цены, error JSON, URL `vs_currencies=rub,usd` ✅  
- кеш/регрессии погоды и валют — прежние тесты зелёные ✅  
- сохранение выбора — через `CryptoCurrency.fromCode` + DataStore key (unit: default/parse codes; **на устройстве не проверялось**)  
- отсутствие лишних запросов при свайпе/переключении — архитектура (`setCryptoCurrency` без сети) + throttle/staleness из 1.3  
- конфликт свайп/клик — click vs drag в Compose (не E2E на устройстве)

## 3. Результаты сборки

```
package: name='com.weathercurrency.app' versionCode='5' versionName='1.4'
minSdkVersion='26' targetSdkVersion='35'
application-label:'Погода и Валюта'
SIGNATURE_VALID
cert SHA-256: 90eaea49f52b8fd11c0cc70df69c88d2f1ba220614fcfbd6e35fc7c4fb42fe54
  (= 1.0…1.3 → обновление поверх; DataStore сохраняется)
permissions: без изменений
```

## 4. Ограничения

1. **Переключатель, сохранение выбора после перезапуска и жесты на реальном Android 15 — не проверялись** (нет устройства/эмулятора на сборочной среде).
2. CoinGecko free tier: возможен HTTP 429 (сообщение в UI; throttle из 1.3).
3. USD в кеше 1.3 (только RUB): после обновления до 1.4 и первого fetch появятся обе стороны; до fetch в USD будет «—» для старого кеша без `priceUsd`.
4. APK — debug-сборка (как и предыдущие задания).

## 5. Путь и SHA-256 APK

| | |
|---|---|
| Путь | **`/opt/weather-currency/output/WeatherCurrency-v1.4.apk`** |
| На телефоне | `/sdcard/Download/WeatherCurrency-v1.4.apk` |
| SHA-256 | **`2fdc6cbd410e67135a377610f5148bda5c068dd924f9fa7d95b649e3b1846ec7`** |
| Размер | **11 959 284** bytes |
| versionName / versionCode | **1.4 / 5** |
| SCP | `scp root@2.27.86.13:/opt/weather-currency/output/WeatherCurrency-v1.4.apk ~/storage/downloads/` |
| `ls -lh` | `-rw-r--r-- 1 root root 12M … WeatherCurrency-v1.4.apk` |

## 6. Изоляция

```bash
$ find /opt/pump-tracker /root/pump-tracker /opt/vpnautoswitch-ru -xdev -mmin -180
(пусто)
```

Другие проекты VPS **не изменялись**; работа только в `/opt/weather-currency`.

---

## ИТОГ

| | |
|---|---|
| BUILD | **SUCCESSFUL** |
| Тесты | **91 / 91 passed** |
| CoinGecko rub,usd | **HTTP 200** (live) |
| APK | `/opt/weather-currency/output/WeatherCurrency-v1.4.apk` |
| SHA-256 | `2fdc6cbd410e67135a377610f5148bda5c068dd924f9fa7d95b649e3b1846ec7` |
| Подпись / поверх 1.3 | да |
| На устройстве | **не проверялось** |
