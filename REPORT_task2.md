# REPORT — TASK №2 — Исправление сетевых ошибок «Погода и Валюта» 1.1

Исполнитель: MiMo Code
Дата: 2026-11-04
Проект: `/opt/weather-currency`
APK: `/opt/weather-currency/output/WeatherCurrency-v1.1.apk`

---

## 1. Корневые причины ошибок

### 1.1 Погода — «Ошибка сети при загрузке погоды»

**Причина:** хост `api.open-meteo.com` (A: `157.90.83.168`) **недоступен по TCP :443** с сетей пользователя и с этого окружения (наблюдение повторено и в Task №1, и при диагностике Task №2):

```
$ curl -w "%{http_code}" --max-time 12 "https://api.open-meteo.com/v1/forecast?..."
000 (timeout)

$ OkHttp (JVM, те же часы):
FAIL SocketTimeoutException: Connect timed out  → api.open-meteo.com
```

IPv6 AAAA для `api.open-meteo.com` не публикуется (DoH — только Authority, без Answer).  
`customer-api.open-meteo.com` → HTTP 401 (нужен API key) — **не использовался**.

В v1.0 клиент имел **один** endpoint; при таймауте `IOException` маскировался в generic-сообщение «Ошибка сети при загрузке погоды».

### 1.2 Валюты — «Ошибка сети при загрузке курсов»

**Причина №1 (главная, код):** парсер XML использовал Xerces-specific `DocumentBuilderFactory.setFeature(...)`. На Android эти фичи часто **не поддерживаются** → `ParserConfigurationException` → перехват в `IOException("ЦБ РФ: ошибка разбора XML")` → в `networkMessage()` сообщение **не содержало** маркеров `HTTP/time/Failed` → UI показывал generic «Ошибка сети при загрузке курсов», **маскируя ошибку парсинга как сетевую**.

**Причина №2:** `decodeCbr(bytes)` **не передавал Content-Type** из HTTP-ответа (charset всегда угадывался).

**Не является причиной:** сам `www.cbr.ru` — с этого окружения OkHttp получает **HTTP 200, 9512 байт, windows-1251** (DDoS-Guard не блокирует наш клиент):

```
OK 200 bytes=9512 ms=501  https://www.cbr.ru/scripts/XML_daily.asp
```

### 1.3 Диагностика по ТЗ

| Проверка | Результат |
|---|---|
| AndroidManifest + INTERNET | есть (Task №1 dump badging) |
| HTTP-клиент | OkHttp 4.12.0 (не Retrofit) |
| Фактические URL | `https://api.open-meteo.com/...`, `https://www.cbr.ru/scripts/XML_daily.asp` |
| TLS | **проверка сертификатов НЕ отключалась**; SSL-ошибки → отдельное сообщение |
| Редиректы | OkHttp default follow; CBR отдаёт 200 без redirect |
| Таймауты | connect 12s, read 18s |
| Кодировка XML | windows-1251 (+ charset из Content-Type) |
| Числа с запятой | `replace(',', '.')` |
| Nominal/Value | `rate = Value / Nominal` |
| USD/EUR/CNY | обязательный порядок, partial OK |
| Дата курса | `ValCurs@Date` |
| Маскировка ошибок | **устранена**: типы `ApiError.Network / Http / Parse / Empty` |

## 2. Внесённые исправления

### 2.1 Новый модуль `ApiError.kt`
- `ApiError.Network` — DNS/connect/timeout/TLS  
- `ApiError.Http(code)` — не-2xx  
- `ApiError.Parse` — JSON/XML/поля  
- `ApiError.Empty` — пустой body  
- `ApiError.fromIOException()` — классификация по типу исключения  

### 2.2 `NetworkDiag.kt`
- `isOnline()` через ConnectivityManager → «Нет подключения к интернету» до запроса  
- `Log.i/e` c тегом `WeatherCurrency`: только класс/сообщение ошибки, **без координат и города**  

### 2.3 Погода — `OpenMeteoClient`
- **Мульти-endpoint** (official, HTTPS, без ключа), по порядку:
  1. `https://api.open-meteo.com` (primary)
  2. `https://historical-forecast-api.open-meteo.com` (fallback, **тот же** `/v1/forecast` schema)
- `customer-api` **не используется**
- Парсер: распознавание `{"error":true,"reason":...}`
- Ошибки: `SocketTimeoutException` / `UnknownHostException` / `SSLException` / `IOException` → `ApiError.Network` с русским текстом

**Проверка fallback вживую (OkHttp, часы Task №2):**

```
api.open-meteo.com                  → FAIL Connect timed out
historical-forecast-api.open-meteo  → OK 200 (current + daily, forecast_days=3)
www.cbr.ru                          → OK 200 bytes=9512
```

Пример payload fallback (HTTP 200):

```json
"current": {"temperature_2m":0.5,"relative_humidity_2m":90,
            "apparent_temperature":-2.7,"weather_code":3,"wind_speed_10m":6.9}
"daily":   {"time":["2026-11-04","2026-11-05","2026-11-06"], ...}
```

### 2.4 Валюты — `CbrCurrencyClient`
- URL оставлен **официальный ЦБ РФ** (не заменён): `https://www.cbr.ru/scripts/XML_daily.asp`
- `setFeature(...)` → **best-effort** `quietlySetFeature()` (Android-совместимо)
- `Content-Type` charset учитывается при декодировании
- Точечные числа: NBSP/пробелы убираются, `,` → `.`
- Ошибки разделены: HTTP ≠ таймаут ≠ разбор XML  

### 2.5 Репозитории
- Сначала `NetworkDiag.isOnline` → «Нет подключения к интернету»
- `ApiError` → конкретные RU-сообщения:
  - нет интернета  
  - сервер недоступен / DNS / TLS / таймаут  
  - `Ошибка HTTP <code>`  
  - `Ошибка обработки ответа …`  
  - геолокация: нет разрешения / выключена / недоступна  
- Логи в Logcat без PII  

### 2.6 Версия
- `versionName = "1.1"`, `versionCode = 2`
- package `com.weathercurrency.app` — **без изменений**
- тот же `signing/debug.keystore` (cert SHA-256 `90eaea49…` совпадает с 1.0 → **установка поверх**)

## 3. Результаты тестов

```bash
$ ./build.sh :app:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL in 6m 17s
```

| Класс | Tests | Failures | Errors |
|---|---|---|---|
| WeatherParsingTest | 12 | 0 | 0 |
| CurrencyParsingTest | 13 | 0 | 0 |
| NetworkErrorTest | 14 | 0 | 0 |
| CacheSerializationTest | 5 | 0 | 0 |
| **TOTAL** | **44** | **0** | **0** |

Новые/обновлённые кейсы: типизация ошибок (timeout/DNS/connect → `ApiError.Network`), HTTP 500 → `ApiError.Http(500)`, мульти-endpoint (нет `customer-api`, наличие fallback-host), NBSP-разделитель в Value ЦБ, charset из Content-Type, error-object Open-Meteo.

## 4. Проверенные API (Task №2, реальные запросы)

| API | Результат |
|---|---|
| `https://api.open-meteo.com/v1/forecast` | **TCP timeout** (недоступен) |
| `https://historical-forecast-api.open-meteo.com/v1/forecast` | **HTTP 200**, schema ок (used as fallback) |
| `https://geocoding-api.open-meteo.com/...` | HTTP 200 |
| `https://archive-api.open-meteo.com/...` | HTTP 200 |
| `https://customer-api.open-meteo.com/...` | HTTP 401 (не используется) |
| `https://www.cbr.ru/scripts/XML_daily.asp` | **HTTP 200**, windows-1251, USD/EUR/CNY на месте |
| `https://api.met.no/...` | 200/403 (рассматривался как alt; **не нужен** — fallback Open-Meteo работает) |

## 5. Ограничения

1. **Проверка на реальном Android-устройстве после сборки 1.1 НЕ выполнялась.** APK скопирован в «Загрузки» телефона, но установка/запуск/нажатие «Обновить» в этой сессии не подтверждены.
2. `api.open-meteo.com` может быть недоступен в будущем снова — fallback покрывает это, но **оба** host’а Open-Meteo теоретически могут упасть одновременно.
3. `historical-forecast-api` — официальный host Open-Meteo с тем же контрактом `/v1/forecast`; семантика данных — forecast-снимки (для UI «текущая + 3 дня» применима, проверено payload’ом).
4. Debug APK (`debuggable=true`) — по заданию Task №1; 1.1 сохраняет тот же стиль сборки.
5. DDoS-Guard на CBR в будущем может начать требовать cookies для нетипичных клиентов — сейчас OkHttp проходит без cookie jar.

## 6. SHA-256 APK

```
fadf4da2f9bd77e682ab15d6acf4c732eaefa3860bf9cd908aabe3d58f69ecb1
```

## 7. Путь к готовому APK

```
/opt/weather-currency/output/WeatherCurrency-v1.1.apk
```

Также скопирован на телефон:

```
/sdcard/Download/WeatherCurrency-v1.1.apk
```

SCP (если нужно с VPS):

```bash
scp root@2.27.86.13:/opt/weather-currency/output/WeatherCurrency-v1.1.apk ~/storage/downloads/
```

### Параметры APK

| Поле | Значение |
|---|---|
| file | `ls -lh` → `-rw-r--r-- … 12M … WeatherCurrency-v1.1.apk` |
| size | **11 926 512** bytes |
| package | `com.weathercurrency.app` |
| versionName | **1.1** |
| versionCode | **2** |
| signature | **VALID**, cert SHA-256 `90eaea49f52b8fd11c0cc70df69c88d2f1ba220614fcfbd6e35fc7c4fb42fe54` (= v1.0 → update-in-place) |
| permissions | INTERNET, ACCESS_NETWORK_STATE, ACCESS_COARSE/FINE_LOCATION |

## 8. Изоляция

```bash
$ find /opt/pump-tracker /root/pump-tracker /opt/vpnautoswitch-ru -xdev -mmin -180
(пусто)
```

Pump Tracker, VpnAutoSwitch и другие проекты **не изменялись**. Все записи — в `/opt/weather-currency`.

---

## ИТОГ

| | |
|---|---|
| BUILD | **SUCCESSFUL** |
| Тесты | **44 / 44 passed** |
| APK | `/opt/weather-currency/output/WeatherCurrency-v1.1.apk` |
| Размер | **11 926 512** bytes |
| SHA-256 | **fadf4da2f9bd77e682ab15d6acf4c732eaefa3860bf9cd908aabe3d58f69ecb1** |
| Установка поверх 1.0 | да (тот же keystore) |
| На реальном телефоне после сборки | **не проверялось** |
