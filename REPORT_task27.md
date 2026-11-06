# REPORT — TASK №27 — WeatherCurrency v2.7: Quick Settings VPN Toggle + публикация обновления

Исполнитель: MiMo Code
Дата: 2026-11-06
Проект: `/opt/weather-currency`
Release: **https://github.com/metanikajkj-netizen/Weather-and-exchange-rates/releases/tag/v2.7**

---

## 0. Подготовка

| Файл | Статус |
|---|---|
| `REPORT_task25.md` | изучен (v2.6: UI + `ProxyEmbedded`) |
| `PROJECT_STATE.md` / `NEXT_SESSION.md` / `DECISIONS.md` | отсутствуют |
| Git | `git init` + commit **без секретов** (`5204b43`); `ProxyEmbedded.kt` в `.gitignore` |
| `gh` (VPS 2.27.86.13) | авторизован как `metanikajkj-netizen` |
| v2.6 | **сохранена** (API `tags/v2.6` → 200) |

---

## 1. Реализованные изменения

### 1.1 Quick Settings VPN Toggle

| Файл | Назначение |
|---|---|
| **`proxy/ProxyVpnTileService.kt`** | **новый** `TileService` + `ProxyTileUi` (pure mapping) |
| **`proxy/VpnStatus.kt`** | **новый** — общие константы статуса (без Android) |
| **`res/drawable/ic_tile_vpn.xml`** | **новый** — белый щит для плитки |
| **`res/values/strings.xml`** | `qs_tile_label` = «Proxy VPN» |
| **`AndroidManifest.xml`** | `<service>` `BIND_QUICK_SETTINGS_TILE`, `QS_TILE`, `ACTIVE_TILE=true` |
| **`proxy/ProxyVpnService.kt`** | `lastStatus` + `STATUS_CONNECTING`; `sendStatus` → `TileService.requestUpdate` |
| **`test/ProxyTileStateTest.kt`** | **новый** — 5 тестов маппинга состояний плитки |

**Поведение:**

1. Одно нажатие → `VpnService.prepare` (согласие — **системный** диалог, не UI приложения) → `ProxyVpnService.start`.
2. Повторное нажатие (включён) → `ProxyVpnService.stop`.
3. Состояния: `STATE_UNAVAILABLE` «Подключение…», `STATE_ACTIVE` «VPN включён», `STATE_INACTIVE` «Proxy VPN» / «Ошибка VPN».
4. Синхронизация: broadcast `ACTION_STATUS` + `lastStatus` + `TRANSPORT_VPN`; `requestListeningState` при каждом `sendStatus`.
5. Заблокированный экран: `unlockAndRun`; Android 14+: `startActivityAndCollapse(PendingIntent)`.
6. **Один движок** — тот же `ProxyVpnService` + `ProxyEmbedded` (v2.6); второй VPN не создаётся.
7. Погода/валюты/крипто/OTA-UI не изменялись.

### 1.2 Система обновлений (анализ, без переписывания)

| Параметр | Значение |
|---|---|
| API | `GET https://api.github.com/repos/metanikajkj-netizen/Weather-and-exchange-rates/releases/latest` |
| Релизы | **только Stable** (не draft, **не pre-release**) |
| Сравнение | **`versionCode`** (число) из `UPDATE_META` vs установленный |
| Метаданные | JSON `UPDATE_META` в **body** релиза: `packageName`, `versionCode`, `versionName`, `apkFileName`, `sha256` |
| APK | asset `WeatherCurrency-v*.apk` (имя из `UPDATE_META.apkFileName`) |
| Уведомление | `UpdateNotifier` → «Доступно обновление WeatherCurrency»; WorkManager ~15 мин |
| Канал OTA-UI | не тронут; детекция — **существующий** механизм |

**Вывод:** v2.7 нужно выпустить **Stable** (не Pre-release), иначе `/releases/latest` её не вернёт. В body **обязателен** `UPDATE_META` (у v2.3–v2.6 его не было — обновления не детектировались).

---

## 2. Сборка и тесты

| Проверка | Результат |
|---|---|
| versionName / versionCode | **2.7 / 18** |
| Kotlin tests | **165/165**, 0 failures (16 классов, включая `ProxyTileStateTest`) |
| Go `./...` | **ok** (движок не менялся) |
| QS_TILE в APK-манифесте | ✅ `ProxyVpnTileService` + `QS_TILE` |
| Подпись vs v2.6 | ✅ **CERT_MATCH** `90eaea49…` |
| Секреты в Git | ✅ `ProxyEmbedded.kt` не отслеживается |

### APK

| | |
|---|---|
| Файл | `/opt/weather-currency/output/WeatherCurrency-v2.7.apk` |
| Размер | 36 426 042 байт |
| **SHA-256** | **`93f54cd291fae4f7bea566a4cc6d9735a6c1e7d19cecad8f95ed6f345af77c3d`** |
| Подпись | `90eaea49f52b8fd11c0cc70df69c88d2f1ba220614fcfbd6e35fc7c4fb42fe54` (CN=WeatherCurrency Debug) |

---

## 3. Публикация GitHub

```text
VPS rsync → /root/release-v2.7/ (APK + SHA256SUMS + RELEASE_BODY)
sha256sum -c → OK
gh release create v2.7 … --title "WeatherCurrency v2.7"   # БЕЗ --prerelease → Stable
CREATE_EXIT=0
```

**UPDATE_META в body релиза:**

```json
{"packageName":"com.weathercurrency.app","versionCode":18,"versionName":"2.7","apkFileName":"WeatherCurrency-v2.7.apk","sha256":"93f54cd291fae4f7bea566a4cc6d9735a6c1e7d19cecad8f95ed6f345af77c3d"}
```

Пароль прокси и другие секреты в релизе **не опубликованы**.

---

## 4. Как WeatherCurrency обнаружит v2.7

1. WorkManager `UpdateCheckWorker` (~15 мин) или кнопка «Проверить обновления».
2. `GitHubReleaseClient.fetchLatest()` → `GET …/releases/latest` → теперь **v2.7** (Stable).
3. `UpdateLogic.parse` извлекает **`UPDATE_META`** (vc=**18** > 17).
4. `UpdateNotifier.notifyUpdateAvailable` / экран «Обновление приложения» → скачать → `ApkVerifier` (имя/sha256/versionCode) → установка.

Не создавались новые каналы уведомлений; Telegram не использовался.

---

## 5. Проверка GitHub API (после публикации)

| Проверка | Результат |
|---|---|
| `tags/v2.7` | ✅ 200, name `WeatherCurrency v2.7` |
| **prerelease** | **false** (Stable — канал `/releases/latest`) |
| draft | false |
| Assets | ✅ `WeatherCurrency-v2.7.apk` 36 426 042 B + `SHA256SUMS.txt` |
| APK скачан | ✅ SHA-256 **совпал** |
| **`/releases/latest`** | ✅ **v2.7**, `UPDATE_META` в body |
| Старые релизы | ✅ v2.2=200, v2.6=200 (не удалены, APK не перезаписаны) |

**Прямые ссылки:**

- Релиз: https://github.com/metanikajkj-netizen/Weather-and-exchange-rates/releases/tag/v2.7
- APK: https://github.com/metanikajkj-netizen/Weather-and-exchange-rates/releases/download/v2.7/WeatherCurrency-v2.7.apk

**Примечание по OTA:** `/releases/latest` **изменился** с v2.2 → **v2.7** (требование задачи: обновление должно быть найдено приложением). Манифест OTA-кода не менялся.

---

## 6. Известные ограничения

1. **Уведомление на телефоне не подтверждено** — нужен пользовательский тест (авторизация проверена, но реальный запуск WorkManager/уведомления на устройстве не выполнялся).
2. Плитка Quick Settings **не проверялась визуально** на Android 15 / Xiaomi HyperOS (код соответствует стандартному `TileService`; возможны OEM-ограничения добавления плитки).
3. Согласие `VpnService.prepare` с плитки открывает **системный** диалог; повторное нажатие после разрешения.
4. Детекция обновления — **Stable-канал**; Pre-release v2.3–v2.6 остаются для ручной установки.
5. `ProxyEmbedded.kt` исключён из Git (секрет); билд требует этот локальный файл.

---

## ИТОГ

| | |
|---|---|
| QS Tile | ✅ `TileService`, toggle, состояния, sync |
| Движок v2.6 | ✅ не дублировался / не переписан |
| Тесты | ✅ Kotlin **165/165**, Go **ok** |
| APK | **2.7/18**, SHA `93f54cd2…`, cert `90eaea49…` |
| Release | **v2.7 Stable**, `UPDATE_META` vc=18 |
| `/releases/latest` | **v2.7** — приложение найдёт обновление |
| Уведомление на телефоне | **не утверждается** — ожидается тест пользователя |

**Завершено. Жду подтверждения пользователя по плитке и уведомлению об обновлении.**
