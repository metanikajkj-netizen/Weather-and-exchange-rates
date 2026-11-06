# REPORT — TASK №10 — Диагностическая проверка обновлений каждые 60 с (v1.9)

Исполнитель: MiMo Code
Дата: 2026-11-05
Проект: `/opt/weather-currency`
APK: `/opt/weather-currency/output/WeatherCurrency-v1.9.apk`

---

## 1. Подготовка

| Проверка | Результат |
|---|---|
| `PROJECT_STATE.md` / `NEXT_SESSION.md` / `DECISIONS.md` | **отсутствуют** |
| `REPORT_task8.md`, `REPORT_task9.md` | изучены (WorkManager 6ч, OTA, v1.8) |
| Версия до задачи | **1.8 / versionCode 9** |
| Целевая версия | **1.9 / versionCode 10** |
| Keystore | **не изменён** (mtime 2026-11-04 21:09) |
| Среда | Ubuntu proot-distro в Termux (не VPS) |

## 2. Изменённые файлы

| Файл | Изменение |
|---|---|
| `app/build.gradle.kts` | `versionName=1.9`, `versionCode=10` |
| `update/UpdateAutoLogic.kt` | `testModeEnabled` (default **false**), `TEST_CHECK_INTERVAL_MS=60_000`, `shouldRunDiagnosticCheck`, `diagnosticIntervalMs` |
| `data/CacheStore.kt` | ключ DataStore `update_test_mode_60s` |
| `update/UpdateScheduler.kt` | `enqueueImmediateCheck` — unique **OneTimeWork** + `NetworkType.CONNECTED`, policy **KEEP** |
| `update/UpdateViewModel.kt` | `DiagnosticUiState`, foreground lifecycle, 60s-цикл, `runBackgroundCheckNow` + статус WorkInfo |
| `ui/UpdateScreen.kt` | переключатель тестового режима, карточка диагностики, кнопка фоновой проверки |
| `MainActivity.kt` | `onStart`/`onStop` → `onAppForegrounded`/`onAppBackgrounded` |
| `test/UpdateDiagnosticLogicTest.kt` | **новый** — 10 тестов |

## 3. Диагностический режим

### 3.1 Переключатель
- «Тестовый режим: проверка каждую минуту» — **по умолчанию выключен**.
- Хранится в DataStore, переживает перезапуск.

### 3.2 Поведение при включении
- Пока приложение **на переднем плане** (`Activity.onStart`/`onStop`): корутина `viewModelScope` + `delay(60_000)`.
- **Без** бесконечных потоков, **без** WakeLock, **без** фонового сервиса.
- При `onStop` — `diagnosticJob.cancel()` (проверки останавливаются).
- **Нет параллельных запросов**: флаг `requestInFlight` + `shouldRunDiagnosticCheck(..., requestInFlight)`.
- Один запрос → `GitHubReleaseClient` + `UpdateLogic.decide` (тот же URL, что production).
- Уведомления — через `shouldNotify` (**дедуп** той же версии).
- **APK не скачивается** автоматически.

### 3.3 Диагностические сведения (экран «Обновление»)
- Время последней проверки (`dd.MM.yyyy HH:mm:ss`)
- Результат последней проверки
- Найденная версия
- Текст ошибки при сбое

### 3.4 Кнопка «Запустить фоновую проверку сейчас»
- `OneTimeWorkRequestBuilder<UpdateCheckWorker>` + `NetworkType.CONNECTED`
- `enqueueUniqueWork("weather_currency_update_check_once", KEEP, …)`
- Статус: поставлено в очередь → в очереди / выполняется → завершено / ошибка / отменено
- В UI явно: **«немедленное выполнение не гарантируется»**
- Использует существующего `UpdateCheckWorker` (уведомления, dedup, без скачивания)

## 4. Production-механизм сохранён

| Элемент | Статус |
|---|---|
| Periodic Work **6 часов** (`PERIOD_HOURS=6`) | без изменений |
| `NetworkType.CONNECTED` + unique KEEP | без изменений |
| SHA-256 / подпись / packageName / versionCode | `ApkVerifier` не менялся |
| Дедуп уведомлений | `shouldNotify` / watermark |
| Настройки auto-check / notify | сохранены |
| GitHub URL | `.../releases/latest` |
| Погода / валюты / крипта | не менялись |

## 5. Результаты тестов

```bash
$ ./build.sh :app:testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL in 2m 8s
```

| | |
|---|---|
| Всего | **144** |
| Failures / Errors | **0 / 0** |

Новый `UpdateDiagnosticLogicTest` (**10**): default testMode=off; gate (mode/foreground/inFlight); interval=60s; production 6h preserved; notify dedup; worker skip не обходится testMode.

Регрессии: 134 прежних тестов зелёные (включая `UpdateAutoLogicTest` 18).

Остановка в фоне / отсутствие параллельных запросов — покрыты unit-функциями `shouldRunDiagnosticCheck` (instrumentation на устройстве не выполнялось).

## 6. Сборка APK

| | |
|---|---|
| BUILD | **SUCCESSFUL** (`assembleDebug` — как во всех версиях проекта) |
| package | `com.weathercurrency.app` |
| versionName / versionCode | **1.9 / 10** |
| Подпись | **SIGNATURE_VALID** |
| Размер | **12 520 421** bytes |
| SHA-256 | **`ccefc5925b4666ff52d18d424d26bbe4d6c5f2a6da8d1e2500cda0ca64c767f5`** |
| Путь | `/opt/weather-currency/output/WeatherCurrency-v1.9.apk` |

## 7. Сертификат v1.8 ↔ v1.9

```text
v1.8 SHA-256: 90eaea49f52b8fd11c0cc70df69c88d2f1ba220614fcfbd6e35fc7c4fb42fe54
v1.9 SHA-256: 90eaea49f52b8fd11c0cc70df69c88d2f1ba220614fcfbd6e35fc7c4fb42fe54
diff → CERTS_IDENTICAL_V18_V19
```

Установка поверх v1.8: same package + versionCode **10 > 9** + identical cert → **да** (на устройство не устанавливалось).

## 8. Файлы релиза

`/opt/weather-currency/output/release-v1.9/`:
- `WeatherCurrency-v1.9.apk`
- `SHA256SUMS.txt`
- `UPDATE_META.txt`
- `RELEASE_BODY.md`

```
UPDATE_META {"packageName":"com.weathercurrency.app","versionCode":10,"versionName":"1.9","apkFileName":"WeatherCurrency-v1.9.apk","sha256":"ccefc5925b4666ff52d18d424d26bbe4d6c5f2a6da8d1e2500cda0ca64c767f5"}
```
Формат проверен — `META_OK` (совместим с OTA-клиентом 1.5+).

## 9. Ограничения

1. Релиз **не опубликован**, APK на телефон **не устанавливался**.
2. 60s-цикл, OneTimeWork и остановка в фоне — unit + код; **E2E на Android 15 не выполнялось**.
3. WorkManager может отложить OneTimeWork до появления сети — это штатное поведение, в UI отмечено.
4. Сборка debug с тем же stable-keystore.

## 10. Изоляция

```bash
$ find /opt/pump-tracker /root/pump-tracker /opt/vpnautoswitch-ru -xdev -mmin -180
(пусто)
```

Keystore и сторонние проекты не изменялись.

---

## ИТОГ

| | |
|---|---|
| BUILD | **SUCCESSFUL** |
| Тесты | **144 / 144 passed** |
| versionName / versionCode | **1.9 / 10** |
| Сертификат v1.8 == v1.9 | **CERTS_IDENTICAL** (`90eaea49…`) |
| Поверх v1.8 | **да** |
| SHA-256 | `ccefc5925b4666ff52d18d424d26bbe4d6c5f2a6da8d1e2500cda0ca64c767f5` |
| Размер | 12 520 421 bytes |
| Release assets | `/opt/weather-currency/output/release-v1.9/` |
| Диагностика 60s + OneTimeWork | реализованы, default testMode **off** |
| Публикация / установка | **не выполнялись** |
