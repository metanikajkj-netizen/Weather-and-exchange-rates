# REPORT — TASK №4 — Горизонтальное переключение валют и криптовалют (v1.3)

Исполнитель: MiMo Code
Дата: 2026-11-05
Проект: `/opt/weather-currency`
APK: `/opt/weather-currency/output/WeatherCurrency-v1.3.apk`

---

## 1. Реализованные изменения

### 1.1 Горизонтальный пейджер (нижний блок)
- `HorizontalPager` (Compose Foundation) с **2 страницами** (не бесконечная карусель):
  - **0** — «Курсы валют» (USD/EUR/CNY, ЦБ РФ) — начальная страница
  - **1** — «Курсы криптовалют» (BTC/ETH/SOL, CoinGecko)
- Свайп влево/вправо с анимацией pager (по умолчанию).
- Под карточками — **2 индикатора страниц** (точки, активная — primary).
- Верхний блок погоды **не изменён**.
- Индекс страницы сохраняется через `rememberSaveable` + синхронизация `pagerState.settledPage` → переживает поворот Activity.
- Горизонтальные жесты pager не конфликтуют с вертикальным `verticalScroll` (стандартная nested-scroll раскладка Foundation).
- Смена страницы **не** дёргает API: загрузка крипты только при `onCryptoPageSelected` (первая страница / кеш старше 10 мин), плюс явная кнопка «Обновить».

### 1.2 Новая карточка криптовалют
- Заголовок «Курсы криптовалют».
- Строки: ₿ Bitcoin, Ξ Ethereum, ◎ Solana + цена в ₽.
- Источник: `CoinGecko (рыночная цена, не биржевой курс ЦБ)`.
- Время последней успешной загрузки.
- Состояния: загрузка (индикатор), ошибка, «Последние сохранённые данные», кнопка «Обновить» (filled, как у валют).
- Высота карточек адаптивная (`wrap` внутри pager; без фиксированных height).

### 1.3 Модель / данные
| Файл | Роль |
|---|---|
| `model/CryptoModels.kt` | `CryptoRate`, `CryptoSnapshot`, `CryptoResult` |
| `data/CoinGeckoClient.kt` | HTTPS client + parse + классификация ошибок (`ApiError`) |
| `data/CryptoRepository.kt` | online/cache, throttle 20 с, DataStore |
| `data/CacheStore.kt` | + ключ `crypto_json` |
| `MainViewModel.kt` | `CryptoUiState`, `refreshCrypto()`, `onCryptoPageSelected()`, `refreshAll()` включает крипту |
| `ui/Format.kt` | `priceRub()` — тысячи пробелами, дробные значения |
| `ui/MainScreen.kt` | pager + indicators + `CryptoCard` |
| `MainActivity.kt` | wiring новых callback-ов |

### 1.4 Кнопки обновления
- «Обновить» в карточке валют → **только** `refreshCurrency()`.
- «Обновить» в карточке крипты → **только** `refreshCrypto()`.
- Общая (шапка) → `refreshAll()` = погода + валюты + крипта.
- Свайп без кеша/с устаревшим кешем → `onCryptoPageSelected()`; при свежем кеше — без запроса (repo throttle дополнительно режет частые повторы).

## 2. Источник криптовалютных котировок

**CoinGecko Public API (Simple Price) — без ключа.**

```
GET https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum,solana&vs_currencies=rub
```

### Проверка API (реальные запросы с этой сборочной среды)

| Запрос | Результат |
|---|---|
| simple/price ids=btc,eth,sol vs=rub | **HTTP 200** `{"bitcoin":{"rub":6617494},"ethereum":{"rub":199337},"solana":{"rub":7896.29}}` |
| тот же URL с %2C-кодированием (как в приложении) | **HTTP 200**, те же поля |
| CryptoCompare | HTTP 401 (отклонён) |
| Binance (только USDT) | 200, но нет RUB-пары в одном запросе как нужно |

Условия: публичный free tier, без embedded-ключа, rate-limit (HTTP 429) — обрабатывается явно. Платные API и секреты в APK **не внедрялись**.

**Найден и исправлен баг при реализации:** в `buildUrl()` сначала не хватало `/api/v3` — исправлено до релиза, покрыто тестом.

## 3. Обработка данных и ошибок

- Один запрос на BTC+ETH+SOL.
- Парсинг: только положительные `rub`-цены; 0/негатив/отсутствуют — пропуск; если пусто → `ApiError.Parse` (никаких выдуманных/нулевых цен в UI).
- Формат `Format.priceRub`:
  - `≥ 100 000` → `6 617 494 ₽` (пробелы-разделители)
  - `≥ 1` с дробью → `7 896.29 ₽`
  - `< 1` → 6 знаков (не схлопывается в 0)
  - ≤0 / NaN → `—`
- Ошибки: нет интернета, DNS/TLS/timeout, HTTP 429 (лимит), 401/403, 5xx, битый JSON — отдельные русские сообщения.
- Кеш: DataStore `crypto_json` (последний успешный snapshot).
- Авто-повтор: мин. интервал 20 с; стальность при открытии страницы — 10 мин; **нет фонового поллинга**.

## 4. Результаты тестов

```bash
$ ./build.sh :app:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL in 1m 7s
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
| **CryptoFormatTest** | **8** | 0 | 0 |
| **TOTAL** | **85** | **0** | **0** |

Покрытие Task №4:
- парсинг BTC/ETH/SOL, порядок, partial, 0/−цены, error JSON, URL HTTPS ✅  
- форматирование RUB (крупные/дробные/ноль) ✅  
- ошибки API (429/401/tls/timeout) — через `ApiError` + прежние NetworkErrorTest ✅  
- кеширование — `CacheSerializationTest` + crypto save/load в CacheStore ✅  
- регрессии погоды/валют — все старые тесты зелёные ✅  
- переключение/сохранение страницы pager — **только код** (см. ограничения)

## 5. Результаты сборки

```
package: name='com.weathercurrency.app' versionCode='4' versionName='1.3'
minSdkVersion='26' targetSdkVersion='35'
application-label:'Погода и Валюта'
SIGNATURE_VALID
cert SHA-256: 90eaea49f52b8fd11c0cc70df69c88d2f1ba220614fcfbd6e35fc7c4fb42fe54
  (= 1.0/1.1/1.2 → обновление поверх, данные DataStore сохраняются)
permissions: INTERNET, ACCESS_NETWORK_STATE, ACCESS_COARSE/FINE_LOCATION (без новых)
```

## 6. Ограничения

1. **Свайп pager, поворот экрана, жесты на реальном Android 15 для 1.3 не проверялись** (нет устройства/эмулятора на сборочной среде).
2. CoinGecko free tier может отдать **429** при частых запросах — UI показывает понятную ошибку; throttle 20 с снижает риск, но не гарантирует отсутствие 429 при агрессивном использовании.
3. Цены — рыночные CoinGecko, **не** курс ЦБ РФ (в UI подписано).
4. APK — debug-сборка (как предыдущие версии задания).
5. Локализация названий монет — англ. (Bitcoin/Ethereum/Solana) + русский интерфейс карточки.

## 7. Путь и SHA-256 APK

| | |
|---|---|
| Путь | **`/opt/weather-currency/output/WeatherCurrency-v1.3.apk`** |
| На телефоне | `/sdcard/Download/WeatherCurrency-v1.3.apk` |
| SHA-256 | **`1c96bde424e72c0434d546e1946b84dd9474f51a2b42ca3a705174ff7e69e999`** |
| Размер | **11 997 124** bytes |
| versionName / versionCode | **1.3 / 4** |
| SCP | `scp root@2.27.86.13:/opt/weather-currency/output/WeatherCurrency-v1.3.apk ~/storage/downloads/` |

## 8. Изоляция

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
| Тесты | **85 / 85 passed** |
| API CoinGecko | **HTTP 200** (проверено live) |
| APK | `/opt/weather-currency/output/WeatherCurrency-v1.3.apk` |
| SHA-256 | `1c96bde424e72c0434d546e1946b84dd9474f51a2b42ca3a705174ff7e69e999` |
| Подпись / поверх 1.2 | да (тот же keystore) |
| Pager на устройстве | **не проверялся визуально** |
