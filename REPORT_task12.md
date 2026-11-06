# REPORT — TASK №12 — WeatherCurrency v2.1: интервал обновлений 15 минут

Исполнитель: MiMo Code
Дата: 2026-11-05
Проект: `/opt/weather-currency`
APK: `/opt/weather-currency/output/WeatherCurrency-v2.1.apk`
Release: https://github.com/metanikajkj-netizen/Weather-and-exchange-rates/releases/tag/v2.1

---

## 1. Подготовка

| Файл | Статус |
|---|---|
| `PROJECT_STATE.md` / `NEXT_SESSION.md` / `DECISIONS.md` | **отсутствуют** |
| `REPORT_task10.md` | прочитан (диагностика 60s, WorkManager 6ч) |
| `REPORT_task11.md` | прочитан (v2.0, SSH/gh публикация) |
| Версия до задачи | 2.0 / versionCode 11 |
| Целевая версия | **2.1 / versionCode 12** |
| Телефон | v1.9 (не устанавливался новый APK) |

## 2. Изменённые файлы

| Файл | Изменение |
|---|---|
| `app/build.gradle.kts` | `versionName=2.1`, `versionCode=12` |
| `update/UpdateAutoLogic.kt` | `PERIOD_MINUTES=15`, `STARTUP_CHECK_INTERVAL_MS=15 мин`; удалены `TEST_CHECK_INTERVAL_MS`, `testModeEnabled`, `shouldRunDiagnosticCheck`, `diagnosticIntervalMs` |
| `update/UpdateScheduler.kt` | PeriodicWork **15 мин**, **`ExistingPeriodicWorkPolicy.UPDATE`** (миграция 6ч→15м без дублей); `cancelUniqueWork(UNIQUE_ONCE_WORK_NAME)` при schedule; удалён `enqueueImmediateCheck` |
| `data/CacheStore.kt` | удалено чтение/запись `testModeEnabled`; legacy-ключ `update_test_mode_60s` **удаляется** при save |
| `update/UpdateViewModel.kt` | полностью удалён диагностический код (DiagnosticUiState, 60s-цикл, runBackgroundCheckNow, observeOnceWork, foreground hooks) |
| `ui/UpdateScreen.kt` | удалена карточка «Диагностика обновлений»; подпись про 15 минут |
| `MainActivity.kt` | удалены `onStart/onStop` → diagnostic, `onTestModeChange`, `onRunBackgroundCheck` |
| `test/UpdateDiagnosticLogicTest.kt` | **удалён** (10 тестов) |
| `test/UpdateAutoLogicTest.kt` | тесты интервала → 15 минут |

**Не менялись:** погода, валюты, криптовалюты, packageName, keystore, GitHub URL, OTA-клиент, уведомления, диалог, `ApkVerifier` (SHA-256/подпись).

## 3. WorkManager — 15 минут

```kotlin
PeriodicWorkRequestBuilder<UpdateCheckWorker>(PERIOD_MINUTES /*15*/, TimeUnit.MINUTES)
    .setConstraints(CONNECTED)
    .setInitialDelay(1, MINUTES)

enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
cancelUniqueWork(UNIQUE_ONCE_WORK_NAME)  // миграция: убрать диагностический OneTimeWork v1.9
```

| Требование | Статус |
|---|---|
| Интервал 15 мин | ✅ `PERIOD_MINUTES = 15` |
| `NetworkType.CONNECTED` | ✅ |
| Уникальное периодическое задание | ✅ то же `UNIQUE_WORK_NAME` |
| Миграция существующего 6ч задания | ✅ **`ExistingPeriodicWorkPolicy.UPDATE`** заменяет в place, без дублей |
| Отключение auto → cancel; включение → schedule | ✅ `applyAutoCheckEnabled` |
| Отмена leftover OneTimeWork | ✅ `cancelUniqueWork(UNIQUE_ONCE_WORK_NAME)` в `schedule()` |
| Без WakeLock / foreground-service / AlarmManager | ✅ |
| Гарантия «ровно каждые 15 мин» | **не обещается** — фактическое расписание контролирует Android (Doze и т.п.) |

## 4. Диагностический режим — удалён

Удалено из UI:
- переключатель «Тестовый режим: проверка каждую минуту»
- кнопка «Запустить фоновую проверку сейчас»
- диагностическая панель (время, результат, версия, ошибка, статус Work)

Удалено из кода:
- `DiagnosticUiState`, 60s-цикл, `onAppForegrounded/Backgrounded`
- `runBackgroundCheckNow`, `observeOnceWork`, `enqueueImmediateCheck`
- `UpdateDiagnosticLogicTest` (10 тестов)
- поле `testModeEnabled` из настроек; legacy DataStore-ключ удаляется при сохранении

**Сохранено:** общая OTA-логика, production-уведомления, периодика 15 мин, startup-проверка, диалог.

## 5. Проверка при запуске

- `STARTUP_CHECK_INTERVAL_MS` = **15 минут** (ранее 6 ч).
- Условие: `autoCheck` включён **и** с последней проверки прошло ≥15 мин (или проверки не было).
- Не блокирует UI; параллельных запросов нет (guard `phase == Checking`).
- Диалог «Обновить / Позже» — без изменений (postpone 24 ч, dedup по versionCode).

## 6. Уведомления и интерфейс

Сохранены: NotificationChannel, POST_NOTIFICATIONS, переход на экран обновления, dedup по versionCode, настройки notify/auto.

Подпись настройки:
> Фоновая проверка примерно каждые 15 минут при наличии интернета. Android может задерживать выполнение

Подпись уведомлений:
> Системные уведомления при выходе новой версии

## 7. Сборка и тесты

```bash
$ ./build.sh :app:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL

$ ./build.sh :app:testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL in 1m 59s
```

| | |
|---|---|
| BUILD | **SUCCESSFUL** |
| Тесты | **134 / 134 passed**, failures=0, errors=0 |

(134 = 144 − 10 удалённых диагностических.)  
`UpdateAutoLogicTest` включает `periodIsFifteenMinutes` (15 мин periodic + 15 мин startup).

### Миграция WorkManager (анализ)
- Существующее unique-имя не менялось → `UPDATE` перезаписывает spec 6ч → 15м **без второго periodic-work**.
- Остаточный `weather_currency_update_check_once` отменяется при каждом `schedule()`.
- Unit-покрытие: константы интервала + `applyAutoCheckEnabled` (cancel/schedule). Инструментальная проверка на устройстве не выполнялась.

## 8. Параметры APK

| Параметр | Значение |
|---|---|
| package | `com.weathercurrency.app` |
| versionName / versionCode | **2.1 / 12** |
| Подпись | **SIGNATURE_VALID** |
| Размер | **12 504 037** bytes |
| SHA-256 | **`6a4bcc97232fe027159f4a04655ca92810300448a48c1b68f78afbfc394e27a4`** |
| Путь | `/opt/weather-currency/output/WeatherCurrency-v2.1.apk` |

### Сертификат v2.0 ↔ v2.1
```
cert SHA-256: 90eaea49f52b8fd11c0cc70df69c88d2f1ba220614fcfbd6e35fc7c4fb42fe54 (оба)
diff → CERTS_IDENTICAL_V20_V21
```

### Установка поверх
| Цель | versionCode | Сертификат | Итог |
|---|---|---|---|
| v1.9 | 12 > 10 | identical | ✅ совместима |
| v2.0 | 12 > 11 | identical | ✅ совместима |

APK на телефон **не устанавливался**.

## 9. SSH / SCP

```bash
$ ssh -o BatchMode=yes -i /root/.ssh/id_ed25519 root@2.27.86.13 'echo SSH_OK'
SSH_OK

$ scp -r release-v2.1 root@2.27.86.13:/root/release-v2.1
SCP_OK

$ ssh … 'cd /root/release-v2.1 && sha256sum -c SHA256SUMS.txt'
WeatherCurrency-v2.1.apk: OK
```

UPDATE_META на VPS: versionCode=12, sha256=`6a4bcc97…e27a4` — совпадает.

## 10. Публикация GitHub

Перед публикацией: `gh release view v2.1` → **RELEASE_ABSENT** (релиза не было, ничего не перезаписывалось).

```bash
gh release create v2.1 WeatherCurrency-v2.1.apk SHA256SUMS.txt UPDATE_META.txt \
  --repo metanikajkj-netizen/Weather-and-exchange-rates --target main \
  --title "WeatherCurrency v2.1" --notes-file RELEASE_BODY.md
→ https://github.com/metanikajkj-netizen/Weather-and-exchange-rates/releases/tag/v2.1
CREATE_EXIT=0
```

### Проверки после публикации

| Проверка | Результат |
|---|---|
| Release v2.1 | ✅ существует |
| draft / prerelease | ✅ false / false |
| Вложения (3) | ✅ APK, SHA256SUMS.txt, UPDATE_META.txt |
| SHA-256 скачанного APK | ✅ `6a4bcc97232fe027159f4a04655ca92810300448a48c1b68f78afbfc394e27a4` |
| UPDATE_META в body | ✅ versionCode=**12**, versionName=**2.1** |
| `/releases/latest` | ✅ **tag=v2.1**, versionCode 12 |

**Прямая ссылка:** https://github.com/metanikajkj-netizen/Weather-and-exchange-rates/releases/tag/v2.1

## 11. Ограничения

1. APK на телефон **не устанавливался** (остаётся v1.9 — сможет найти v2.1 через OTA).
2. Точный запуск WorkManager «каждые ровно 15 минут» не гарантируется Android.
3. Миграция 6ч→15м и отмена once-work unit-покрыты; on-device не проверялись.
4. Сборка debug с тем же stable-keystore.

## 12. Изоляция

```bash
$ find /opt/pump-tracker /root/pump-tracker /opt/vpnautoswitch-ru -xdev -mmin -180
(пусто)
```

Keystore не изменён (mtime 2026-11-04 21:09). Сторонние проекты не тронуты. Секреты SSH в отчёт не выносились.

---

## ИТОГ — **ПОЛНЫЙ УСПЕХ**

| Этап | Статус |
|---|---|
| versionName/versionCode = 2.1 / 12 | ✅ |
| Интервал WorkManager **15 минут** + UPDATE-миграция | ✅ |
| Startup-check **15 минут** | ✅ |
| Диагностический режим **удалён** | ✅ |
| Уведомления / диалог / OTA / SHA-256 | ✅ сохранены |
| BUILD SUCCESSFUL | ✅ |
| Тесты | **134 / 134** |
| Сертификат v2.0 == v2.1 | ✅ |
| Поверх v1.9 и v2.0 | ✅ |
| SHA-256 | `6a4bcc97232fe027159f4a04655ca92810300448a48c1b68f78afbfc394e27a4` |
| SSH / SCP / sha256sum -c | ✅ OK |
| GitHub Release v2.1 | ✅ **опубликован** |
| `/releases/latest` → v2.1, versionCode 12 | ✅ |
| Телефон | APK **не устанавливался** (v1.9) |
