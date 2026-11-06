# REPORT — TASK №1 — «Погода и Валюта»

Исполнитель: MiMo Code
Дата: 2026-11-04
Проект: `/opt/weather-currency`
APK: `/opt/weather-currency/output/WeatherCurrency-v1.0.apk`

---

## 1. VPS и build environment

Команды аудита и результаты:

```bash
$ uname -a
Linux localhost 6.17.0-PRoot-Distro #1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000 aarch64 GNU/Linux

$ cat /etc/os-release | head -2
PRETTY_NAME="Ubuntu 26.04 LTS"
NAME="Ubuntu"

$ nproc
8

$ free -h | head -2
               total        used        free      shared  buff/cache   available
Mem:            10Gi       8.0Gi       604Mi        28Mi       2.4Gi       2.9Gi

$ df -h / | tail -1
/dev/block/dm-48  224G  108G  116G  49% /
```

Свободное место: ~116 ГБ. RAM: ~2.9 ГБ доступно (сборка ограничена `-Xmx2048m`, `workers.max=2`, `--no-daemon`).

Существующий Android build toolchain (найден, переиспользован read-only):

| Компонент | Путь | Версия |
|---|---|---|
| JDK | `/opt/vpnautoswitch-ru/build/jdk/jdk-17.0.21+9` | OpenJDK 17.0.21 (Temurin) |
| Gradle | `/opt/vpnautoswitch-ru/build/sdk/gradle-8.14/bin/gradle` | 8.14 |
| Android SDK platforms | `/opt/vpnautoswitch-ru/build/sdk-root/platforms` | android-37 / android-37.0 (API 37) |
| Build-tools | `/opt/vpnautoswitch-ru/build/sdk-root/build-tools/35.0.0` | 35.0.0 |
| aapt2/zipalign (x86_64) | qemu-x86_64 + x86root wrapper | работает |

Переустановка рабочих компонентов НЕ выполнялась. Изолированная точка входа: `/opt/weather-currency/build/sdk` (симлинки + обёртки qemu для aapt2/zipalign/aapt) и `/opt/weather-currency/build.sh`.

## 2. Использованные версии

```
$ java -version
openjdk version "17.0.21" 2026-10-20
OpenJDK Runtime Environment Temurin-17.0.21+9

$ gradle --version | grep -E "Gradle|Kotlin|JVM"
Gradle 8.14
Kotlin:        2.0.21   (встроенный в Gradle; проект использует Kotlin plugin 2.4.20)
Launcher JVM:  17.0.21
```

- Android Gradle Plugin: **8.13.2**
- Kotlin: **2.4.20** (+ compose compiler plugin 2.4.20)
- compileSdk: **37** (`compileSdkMinor=0` → платформа android-37.0)
- minSdk: **26**, targetSdk: **35**
- Compose BOM: **2025.10.01**
- OkHttp **4.12.0**, Gson **2.11.0**, DataStore Preferences **1.1.7**
- JUnit **4.13.2**, MockWebServer **4.12.0**

Maven/Google repos доступны (проверено `curl -I` → HTTP 200 для `dl.google.com` и `repo.maven.apache.org`).

## 3. Структура проекта

```
/opt/weather-currency/
├── REPORT_task1.md
├── build.sh                          # изолированная сборка (JDK/SDK/qemu aapt2)
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── local.properties                  # sdk.dir=/opt/weather-currency/build/sdk
├── signing/debug.keystore            # стабильный debug-ключ (не публикуется)
├── output/WeatherCurrency-v1.0.apk
├── build/sdk/                        # изолированный SDK (симлинки + qemu-обёртки)
│   ├── platforms -> …/vpnautoswitch-ru/…/platforms
│   ├── licenses, platform-tools
│   └── build-tools/35.0.0/           # обёртки aapt2/zipalign/aapt
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── res/  (strings, themes light/night, drawable icon)
        │   └── java/com/weathercurrency/app/
        │       ├── MainActivity.kt
        │       ├── MainViewModel.kt
        │       ├── data/
        │       │   ├── OpenMeteoClient.kt
        │       │   ├── CbrCurrencyClient.kt
        │       │   ├── WeatherRepository.kt
        │       │   ├── CurrencyRepository.kt
        │       │   ├── CacheStore.kt
        │       │   └── WeatherCodes.kt
        │       ├── model/ (WeatherModels.kt, CurrencyModels.kt)
        │       ├── location/ (LocationProvider.kt, CityResolver.kt)
        │       └── ui/ (MainScreen.kt, theme/Theme.kt)
        └── test/java/com/weathercurrency/app/
            ├── WeatherParsingTest.kt
            ├── CurrencyParsingTest.kt
            ├── NetworkErrorTest.kt
            └── CacheSerializationTest.kt
```

Архитектура: UI (Compose) → ViewModel (StateFlow) → Repository → API client (OkHttp) + CacheStore (DataStore).

## 4. Реализованные функции

### Погода (Open-Meteo)
- Запрос разрешения `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` при первом обновлении погоды (ActivityResultContracts).
- Одноразовое получение координат (LocationManager: `getCurrentLocation` API 30+, иначе `requestSingleUpdate`), таймаут 12 с, без фонового отслеживания.
- Обработка: отказ в разрешении, выключенные провайдеры, таймаут, недоступный сервис.
- Название города: Android Geocoder (async API 33+, иначе sync), fallback — координаты `%.4f, %.4f`. Выдуманные города не подставляются.
- Текущая погода: температура, ощущаемая, влажность, ветер км/ч, текстовое описание WMO-кода на русском, эмодзи-иконка, время обновления.
- Прогноз на 3 календарных дня: день недели, иконка, min/max, описание; `timezone=auto`.
- Кнопка «Обновить» (и «Обновить всё» в шапке) — без циклов запросов (guard по `loading`).
- Ошибки сети/геолокации → сообщение на русском; при наличии кеша показывается «Последние сохранённые данные».

### Валюты (ЦБ РФ)
- Официальный HTTPS: `https://www.cbr.ru/scripts/XML_daily.asp`.
- USD / EUR / CNY; `Value / Nominal` → цена за 1 единицу, формат `XX.XXXX ₽`.
- Дата официального курса (`ValCurs@Date`), время последней загрузки, источник «ЦБ РФ (официальный курс, не биржевой)».
- Ручное обновление кнопкой.
- Кеширование последнего успешного ответа; офлайн — сохранённые значения с их датой (не нули).

## 5. Источники данных

| Данные | Источник | URL (HTTPS) |
|---|---|---|
| Погода/прогноз | Open-Meteo | `https://api.open-meteo.com/v1/forecast` |
| Город по координатам | Android Geocoder (offline/fallback) | — |
| Курсы валют | ЦБ РФ | `https://www.cbr.ru/scripts/XML_daily.asp` |

Документация Open-Meteo: https://open-meteo.com/en/docs

## 6. Реализация геолокации

- Только foreground-разрешения: `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`.
- `ACCESS_BACKGROUND_LOCATION` **не запрашивается** (проверено grep + dump манифеста).
- Один фикс на запрос; fallback на последние известные координаты (< 5 мин) при их наличии.
- Провайдеры: NETWORK → PASSIVE → GPS; таймаут 12 с.
- Координаты в production-логи не пишутся; в кеше хранятся только для повторного запроса погоды.

## 7. Реализация кеширования

- `CacheStore` на **DataStore Preferences** (`weather_currency_cache`).
- JSON-снапшоты weather/currency (Gson), сохраняются только после успешной загрузки.
- Bootstrap: сначала кеш → затем фоновый refresh.
- При ошибке сети UI показывает кеш + метку «Последние сохранённые данные».

## 8. Особенности интерфейса

- Material 3, светлая/тёмная тема (системная), русский язык.
- Один экран, без нижней навигации; прокрутка `verticalScroll`.
- Карточки со скруглением 20 dp; адаптивные отступы; поддержка fontScale (без фиксированных height).
- Стейты: загрузка (индикатор), успех, ошибка сети, нет разрешения, нет данных, кеш.
- Сетевые запросы не блокируют UI (Coroutines + StateFlow + `collectAsStateWithLifecycle`).

## 9. Android-разрешения

Из `aapt2 dump badging`:

```
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
uses-permission: name='android.permission.ACCESS_COARSE_LOCATION'
uses-permission: name='android.permission.ACCESS_FINE_LOCATION'
uses-permission: name='com.weathercurrency.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'  # auto, androidx
```

Нет: background location, камеры, микрофона, рекламных/аналитических SDK.

## 10. Результаты unit-тестов

Команда:

```bash
$ ./build.sh :app:testDebugUnitTest
BUILD SUCCESSFUL in 1m 12s
```

JUnit XML (`app/build/test-results/testDebugUnitTest/`):

| Класс | Tests | Failures | Errors |
|---|---|---|---|
| WeatherParsingTest | 9 | 0 | 0 |
| CurrencyParsingTest | 10 | 0 | 0 |
| NetworkErrorTest | 9 | 0 | 0 |
| CacheSerializationTest | 5 | 0 | 0 |
| **TOTAL** | **33** | **0** | **0** |

Покрытие по ТЗ:
- парсинг Open-Meteo, weather codes → RU, °C, 3-дневный прогноз, отсутствующие поля, мусорный JSON — **да**
- ошибки сети (HTTP 500, timeout, connection refused) — **да** (MockWebServer)
- отказ в геолокации / кеш UI — логика в ViewModel/Repository; unit-покрытие кеша — сериализация round-trip (**да**); живой отказ геолокации — только на устройстве
- парсинг ЦБ, USD/EUR/CNY, Nominal, отсутствие валюты, битый XML, windows-1251, дата курса, кеширование round-trip — **да**

## 11. Результаты сборки

```bash
$ ./build.sh :app:assembleDebug :app:testDebugUnitTest
...
> Task :app:assembleDebug
BUILD SUCCESSFUL in 1m 58s
43 actionable tasks: 18 executed, 25 up-to-date
```

Kotlin compilation: OK. Compose compilation: OK. `processDebugManifest`: OK.

## 12. Результаты проверки APK

```bash
$ ls -la /opt/weather-currency/output/WeatherCurrency-v1.0.apk
-rw-r--r-- 1 root root 12748284 … /opt/weather-currency/output/WeatherCurrency-v1.0.apk

$ apksigner verify --print-certs …
Signer #1 certificate DN: CN=WeatherCurrency Debug, OU=WeatherCurrency, O=WeatherCurrency, L=NA, C=RU
Signer #1 certificate SHA-256 digest: 90eaea49f52b8fd11c0cc70df69c88d2f1ba220614fcfbd6e35fc7c4fb42fe54
$ apksigner verify … && echo SIGNATURE_VALID
SIGNATURE_VALID

$ aapt2 dump badging … | grep package
package: name='com.weathercurrency.app' versionCode='1' versionName='1.0' … compileSdkVersion='37'
$ … | grep sdkVersion
minSdkVersion:'26'
targetSdkVersion:'35'
$ … | grep application-label:
application-label:'Погода и Валюта'
launchable-activity: name='com.weathercurrency.app.MainActivity'
```

- BUILD: **SUCCESSFUL**
- Подпись: **действительна** (debug keystore, alias `weatherdebug`, validity 10950 дней — установка поверх первых версий)
- Package: `com.weathercurrency.app` ✓
- versionName `1.0`, versionCode `1` ✓
- Разрешения соответствуют ТЗ ✓; `ACCESS_BACKGROUND_LOCATION` отсутствует ✓
- `android:debuggable=true` — это debug APK (по ТЗ: «debug APK для первой версии»)

## 13. Package name и версия

- applicationId / package: **com.weathercurrency.app**
- versionName: **1.0**
- versionCode: **1**
- Название приложения: **Погода и Валюта**

## 14. SHA-256 APK

```
1c2eab313d6ddce3688269cfc6f70c0e0b8850c248fcdc69ae13da1020645af4
```

## 15. Размер файла

```
12 748 284 bytes (≈ 12.16 MiB)
```

## 16. Путь к APK

```
/opt/weather-currency/output/WeatherCurrency-v1.0.apk
```

## 17. Команда скачивания через Termux

```bash
scp root@2.27.86.13:/opt/weather-currency/output/WeatherCurrency-v1.0.apk ~/storage/downloads/
```

После скачивания открыть файл из папки «Загрузки» и установить (разрешить установку из неизвестных источников).

Дополнительные порты не открывались. Публичный HTTP-сервер не создавался. AxiomDevBot для передачи не использовался.

## 18. Известные ограничения

1. **`api.open-meteo.com` недоступен с данного VPS**: DNS (Google/Cloudflare DoH) возвращает `157.90.83.168`, TCP :443/:80 — timeout/packet loss. При этом `geocoding-api.open-meteo.com`, `archive-api.open-meteo.com`, `air-quality-api.open-meteo.com` — HTTP 200. Вывод: сетевая проблема пути/хоста для forecast-эндпоинта с IP VPS, не ошибка клиента. На устройстве пользователя эндпоинт используется как официальный; фактический запрос с телефона здесь не выполнялся.
2. Официальный курс ЦБ РФ — не биржевой realtime; обновляется 1 раз в день (по регламенту ЦБ).
3. Название города зависит от Geocoder на устройстве; при его отсутствии показываются координаты.
4. APK — **debug**-сборка (по заданию), `android:debuggable=true`.
5. Сборка выполнена в proot/aarch64 окружении с qemu-обёртками нативных build-tools (x86_64), как и у существующего проекта VpnAutoSwitch.

## 19. Непроверенные функции

- **Запуск на реальном Android-устройстве не выполнялся** (нет устройства/эмулятора на VPS; эмулятор не запускался из-за нагрузки).
- Живой запрос `api.open-meteo.com` с VPS не прошёл (см. п.18.1) — парсинг проверен на валидных фикстурах формата API и через `archive-api` (реальный ответ Open-Meteo для daily-блока).
- Геолокация, Geocoder, системные диалоги разрешений — проверены кодом/компиляцией, не на устройстве.
- Установка APK на устройство и «повторная установка поверх» keystorem — не выполнены на устройстве.

Не заявляется об успешном запуске на реальном Android.

## 20. Подтверждение отсутствия изменений других проектов

```bash
$ find /opt/pump-tracker /root/pump-tracker /opt/vpnautoswitch-ru -xdev -mmin -120
(пусто)

$ stat -c '%y %n' /opt/pump-tracker /root/pump-tracker /opt/vpnautoswitch-ru
2026-10-13 08:31:04 /opt/pump-tracker
2026-10-16 09:59:56 /root/pump-tracker
2026-11-02 08:29:28 /opt/vpnautoswitch-ru
```

- Pump Tracker (`/opt/pump-tracker`, `/root/pump-tracker`) — **не изменялся**, не останавливался.
- VpnAutoSwitch (`/opt/vpnautoswitch-ru`) — использовался **только на чтение** (JDK, Gradle, SDK, qemu); исходники, БД, конфиги, output — не изменялись (mtime не менялся).
- Глобальное обновление VPS, firewall, DNS, VPN, SSH, перезагрузка, новые публичные порты — **не выполнялись**.
- Все записи изолированы в `/opt/weather-currency`.

---

## ФИНАЛЬНЫЙ РЕЗУЛЬТАТ

1. `/opt/weather-currency/output/WeatherCurrency-v1.0.apk`
2. `/opt/weather-currency/REPORT_task1.md`

| Параметр | Значение |
|---|---|
| BUILD | **SUCCESSFUL** |
| Unit-тесты | **33 / 33 passed**, 0 failures, 0 errors |
| Размер APK | **12 748 284 bytes** |
| SHA-256 | **1c2eab313d6ddce3688269cfc6f70c0e0b8850c248fcdc69ae13da1020645af4** |
| Путь | `/opt/weather-currency/output/WeatherCurrency-v1.0.apk` |
| Скачивание (Termux) | `scp root@2.27.86.13:/opt/weather-currency/output/WeatherCurrency-v1.0.apk ~/storage/downloads/` |
