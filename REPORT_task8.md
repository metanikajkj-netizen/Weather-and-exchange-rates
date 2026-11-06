# REPORT — TASK №8 — Автоматическая проверка обновлений (v1.7)

Исполнитель: MiMo Code
Дата: 2026-11-05
Проект: `/opt/weather-currency`
APK: `/opt/weather-currency/output/WeatherCurrency-v1.7.apk`

---

## 1. Подготовка

| Проверка | Результат |
|---|---|
| `PROJECT_STATE.md` / `NEXT_SESSION.md` / `DECISIONS.md` | **отсутствуют** |
| `REPORT_task6.md`, `REPORT_task7.md` | изучены (OTA, UPDATE_META, keystore, релизы) |
| Версия до задачи | 1.6 / versionCode 7 |
| Целевая версия | **1.7 / 8** |
| GitHub URL | не менялся: `.../releases/latest` |
| Keystore | **не изменён** (mtime 2026-11-04 21:09), новый сертификат не создавался |

## 2. Изменённые и новые файлы

| Файл | Изменение |
|---|---|
| `app/build.gradle.kts` | `versionName=1.7`, `versionCode=8`, dep `androidx.work:work-runtime-ktx:2.10.1` |
| `AndroidManifest.xml` | `POST_NOTIFICATIONS`, `launchMode=singleTop` |
| `update/UpdateAutoLogic.kt` | **новый** — чистая логика: throttle, dedup уведомлений, диалог, настройки |
| `update/UpdateScheduler.kt` | **новый** — unique periodic Work 6ч, `NetworkType.CONNECTED`, `KEEP` |
| `update/UpdateCheckWorker.kt` | **новый** — CoroutineWorker: GitHub → decide → notify; **без скачивания APK** |
| `update/UpdateNotifier.kt` | **новый** — NotificationChannel, PendingIntent → Update screen, single id |
| `data/CacheStore.kt` | настройки OTA в DataStore (auto, notify, lastCheck, dedup, postpone) |
| `MainViewModel.kt` | startup-check (throttle 6ч) + состояние диалога |
| `update/UpdateViewModel.kt` | загрузка/сохранение настроек, schedule/cancel, markChecked |
| `ui/UpdateScreen.kt` | переключатели «Автоматически проверять…» и «Уведомлять…» |
| `MainActivity.kt` | AlertDialog, POST_NOTIFICATIONS, intent из уведомления, schedule |
| `test/UpdateAutoLogicTest.kt` | **новый** — 18 тестов |

Погода, валюты, криптовалюты, packageName, OTA-URL, механизм скачивания/установки — **без изменений**.

## 3. Реализация

### 3.1 Фоновая проверка (WorkManager)
- `PeriodicWorkRequest` **каждые 6 часов**, constraint **`NetworkType.CONNECTED`**.
- `enqueueUniqueUniquePeriodicWork(..., KEEP)` — **без дублей**.
- Doze/батарея: WorkManager → JobScheduler/JobService (штатные ограничения Android), **без** собственного foreground-service, **без** ручного WakeLock-цикла (разрешения `WAKE_LOCK`/`REBOOT`/`FOREGROUND_SERVICE` в APK — стандартный merge из библиотеки WorkManager, не наши сервисы).
- Ошибки сети в воркере → `Result.success()` (лог), **без падения** и без retry-шторма.
- `autoCheck=off` → воркер **не ходит в сеть** (`workerShouldSkip`).
- **APK в фоне не скачивается** — только GET latest release.

### 3.2 Уведомление
- Channel `update_available` (O+), title «Доступно обновление WeatherCurrency», text «Доступна версия X.Y».
- `PendingIntent` → `MainActivity` + `EXTRA_OPEN_UPDATE` → экран «Обновление приложения» (download/install только по кнопке пользователя).
- Android 13+: runtime-запрос `POST_NOTIFICATIONS` при старте; если запрещено — тихо пропускаем.
- **Дедупликация**: один `NOTIFICATION_ID` + watermark `lastNotifiedVersionCode` → нет повторов одной версии; новая версия > watermark → снова уведомление.

### 3.3 Диалог при открытии
- Текст: «Доступно обновление 1.7» / «Вышла новая версия приложения. Хотите обновиться сейчас?»
- Кнопки: **«Обновить»** → экран обновления; **«Позже»** → postpone 24ч + не предлагать ту же версию.
- Проверка при старте: только если `autoCheck` и прошло ≥6ч с `lastCheck` (первый запуск — сразу).
- Диалог **не** показывается при переключении drawer-экранов (только из `MainUiState.updateDialog`).
- `AlertDialog` — модалка внутри activity, не поверх других приложений.

### 3.4 Единая OTA-логика
Все три пути (воркер, startup, ручная кнопка) используют:
- `GitHubReleaseClient` (один URL)
- `UpdateLogic.decide` (versionCode, package, APK asset, draft/prerelease)
- Существующие проверки SHA-256 / подписи / PackageInstaller — **без изменений**.

### 3.5 Настройки (экран «Обновление»)
| Переключатель | По умолчанию | Эффект |
|---|---|---|
| Автоматически проверять обновления | **вкл** | off → `cancelUniqueWork` + startup-check off; ручная проверка работает |
| Уведомлять о новых версиях | **вкл** | off → без system notifications; экран обновления и ручная проверка работают |

Значения в DataStore, переживают перезапуск.

## 4. Результаты тестов

```bash
$ ./build.sh :app:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL in 1m 24s
```

| | |
|---|---|
| Всего | **134** |
| Failures / Errors | **0 / 0** |

Новый `UpdateAutoLogicTest` (**18**): дефолты настроек, skip worker/startup при auto=off, throttle 6ч, notify dedup, postpone 24ч, dialog once-per-version, force reshoot, monotonic watermark.

Регрессии: Weather 12, Currency 13, Network 14, Cache 5, Temperature 12, PlaceLabel 10, Crypto 25, UpdateLogic 16, GitHub 9 — зелёные.

Покрытие: «лишние сетевые запросы» — `workerShouldSkip` + `shouldStartupCheck` (unit) + `ExistingPeriodicWorkPolicy.KEEP`; PendingIntent/Channel — код + `ensureChannel` (instrumentation на устройстве не выполнялся).

## 5. Сборка APK

| | |
|---|---|
| BUILD | **SUCCESSFUL** |
| package | `com.weathercurrency.app` |
| versionName / versionCode | **1.7 / 8** |
| Подпись | **SIGNATURE_VALID** |
| Размер | **12 504 037** bytes |
| SHA-256 | **`cf4d1b11f15aeb7d47de2ebb72e4e6f97847ff252f15880213804515ef443f31`** |
| Путь | `/opt/weather-currency/output/WeatherCurrency-v1.7.apk` |

Разрешения (badging): INTERNET, ACCESS_NETWORK_STATE, LOCATION×2, REQUEST_INSTALL_PACKAGES, **POST_NOTIFICATIONS**, + merge WorkManager (WAKE_LOCK, BOOT_COMPLETED, FOREGROUND_SERVICE — штатные для WorkManager).

## 6. Сертификат v1.6 ↔ v1.7

```
v1.6 SHA-256: 90eaea49f52b8fd11c0cc70df69c88d2f1ba220614fcfbd6e35fc7c4fb42fe54
v1.7 SHA-256: 90eaea49f52b8fd11c0cc70df69c88d2f1ba220614fcfbd6e35fc7c4fb42fe54
diff → CERTS_IDENTICAL
```

**Установка поверх v1.6 без удаления:** same applicationId + versionCode **8 > 7** + identical cert → **да**.  
На телефон не устанавливалось (ограничение).

## 7. Файлы релиза

`/opt/weather-currency/output/release-v1.7/`:
- `WeatherCurrency-v1.7.apk`
- `UPDATE_META.txt`
- `SHA256SUMS.txt`
- `RELEASE_BODY.md` (с UPDATE_META, формат совместим с парсером 1.5+)

```
UPDATE_META {"packageName":"com.weathercurrency.app","versionCode":8,"versionName":"1.7","apkFileName":"WeatherCurrency-v1.7.apk","sha256":"cf4d1b11f15aeb7d47de2ebb72e4e6f97847ff252f15880213804515ef443f31"}
```
Формат проверен (JSON, 64-hex, versionCode=8) — `META_OK`.

## 8. Ограничения

1. **Релиз не опубликован**, APK на телефон **не устанавливался**.
2. WorkManager/уведомления/диалог на реальном Android 15 **не проверялись E2E** (нет устройства).
3. PendingIntent requestCode и singleTop — проверены кодом, не на устройстве.
4. Сборка debug с тем же stable-keystore (как все версии).
5. Если `POST_NOTIFICATIONS` запрещён — воркер пишет лог и не шлёт уведомление (корректно, без краша).

## 9. Изоляция

```bash
$ find /opt/pump-tracker /root/pump-tracker /opt/vpnautoswitch-ru -xdev -mmin -180
(пусто)
```

Keystore не трогали; другие проекты не изменялись.

---

## ИТОГ

| | |
|---|---|
| BUILD | **SUCCESSFUL** |
| Тесты | **134 / 134 passed** |
| versionName / versionCode | **1.7 / 8** |
| Сертификат v1.6 == v1.7 | **CERTS_IDENTICAL** |
| Поверх v1.6 | **да** |
| APK | `/opt/weather-currency/output/WeatherCurrency-v1.7.apk` |
| SHA-256 | `cf4d1b11f15aeb7d47de2ebb72e4e6f97847ff252f15880213804515ef443f31` |
| Release assets | `/opt/weather-currency/output/release-v1.7/` |
| Публикация | **не выполнена** — ждём указаний |
