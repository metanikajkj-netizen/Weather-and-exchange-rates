# REPORT — TASK №6 — Меню и OTA-обновления через GitHub Releases (v1.5)

Исполнитель: MiMo Code
Дата: 2026-11-05
Проект: `/opt/weather-currency`
GitHub: https://github.com/metanikajkj-netizen/Weather-and-exchange-rates
APK: `/opt/weather-currency/output/WeatherCurrency-v1.5.apk`

---

## 1. Подготовка и проверка окружения

| Проверка | Результат |
|---|---|
| Текущая версия перед задачей | versionName **1.4**, versionCode **5** |
| Целевая версия | **1.5 / 6** (свободна, конфликтов нет) |
| GitHub repo | **HTTP 200**, `private: false`, default branch `main` |
| `/releases/latest` | **HTTP 404** (релизов ещё нет) |
| `/releases` list | **HTTP 200**, `[]` (0 релизов) |
| GitHub API create release | **HTTP 401** `Requires authentication` |
| `gh` CLI | **не установлен**, `gh auth` — нет |
| Токен в env | **нет** (`GITHUB_TOKEN` / `GH_TOKEN` не найдены) |
| Keystore | `/opt/weather-currency/signing/debug.keystore` (тот же с 1.0) |
| Бэкап ключа вне git | **создан**: `/root/weathercurrency-keys-backup/debug.keystore` (mode 600) |

Другие проекты (Pump Tracker, VpnAutoSwitch) — **не изменялись**.

## 2. Реализованные изменения

### 2.1 Боковое меню (Navigation Drawer)
- `ModalNavigationDrawer` + `ModalDrawerSheet` в `MainActivity`.
- Кнопка **☰** в левом верхнем углу («Главная» и «Обновление приложения»).
- Пункты:
  - **Главная** — существующий экран (погода, валюты, крипта, свайпы, ₽/$).
  - **Обновление приложения** — версия, проверка, релиз, скачивание/установка.
- Закрытие: свайп по панели, тап вне панели, системная «Назад» (стандартное поведение ModalNavigationDrawer).
- Экран (`home`/`update`) переживает поворот через `rememberSaveable`.

### 2.2 Экран обновлений (`UpdateScreen`)
- Текущая версия: `versionName (versionCode N)`.
- Кнопка **«Проверить обновления»**.
- Состояния:
  - «Установлена последняя версия»
  - «Доступна версия X.X» + версия, tag, размер APK, release notes
  - загрузка с прогрессом, проверка, ошибка / нет интернета
  - **«Скачать и установить»** / **«Установить»**

### 2.3 GitHub Releases API
```
GET https://api.github.com/repos/metanikajkj-netizen/Weather-and-exchange-rates/releases/latest
Accept: application/vnd.github+json
User-Agent: WeatherCurrencyApp/1.5
```
Поля: `tag_name`, `name`, `body` (notes), assets (`name`, `browser_download_url`, `size`), `draft`, `prerelease`.

Только **стабильные** (`draft=false`, `prerelease=false`).

**Метаданные в теле релиза** (машиночитаемые):
```
UPDATE_META {"packageName":"com.weathercurrency.app","versionCode":6,"versionName":"1.5","apkFileName":"WeatherCurrency-v1.5.apk","sha256":"<hex64>"}
```
- Сравнение версий **только по versionCode** (не по строке versionName).
- Нет `UPDATE_META` → ошибка, не «тихое» обновление.
- Нет APK asset → ошибка.

### 2.4 Скачивание и проверка
- **DownloadManager** (HTTPS URL релиза) → файл в `getExternalFilesDir()`.
- Прогресс 0–100%, обработка fail/interrupt.
- Перед установкой (`ApkVerifier.verifyBeforeInstall`):
  1. файл существует и не пуст  
  2. **SHA-256** == метаданные релиза  
  3. `packageName` APK == `com.weathercurrency.app`  
  4. `versionCode` APK == метаданные  
  5. APK **новее** установленного  
  6. **подпись APK** совпадает с установленной (cert SHA-256)  
- Уже проверенный файл **не перекачивается** повторно.
- Повреждённый / чужой / старый / с другой подписью → отказ, без установки.

### 2.5 Установка
- `FileProvider` (`${applicationId}.fileprovider` + `res/xml/file_paths.xml`).
- `Intent.ACTION_VIEW` + MIME `application/vnd.android.package-archive` → системный PackageInstaller (подтверждение пользователем, отмена, ошибки — на стороне системы).
- **REQUEST_INSTALL_PACKAGES** — только для инициируемого пользователем обновления (обосновано в манифесте комментарием).
- Без root / Magisk / `pm install` / скрытой установки.

### 2.6 Подпись и ключ
- `applicationId = com.weathercurrency.app` — **без изменений**.
- Тот же `signing/debug.keystore` (cert SHA-256 `90eaea49…` = 1.0–1.4) → установка поверх.
- **Новый keystore не создавался.**
- Приватный ключ **не публикуется** в GitHub; бэкап: `/root/weathercurrency-keys-backup/debug.keystore` (вне репозитория).

### 2.7 Изменённые / новые файлы
| Файл | Назначение |
|---|---|
| `update/ReleaseModels.kt` | Модели релиза/метаданных |
| `update/UpdateLogic.kt` | parse UPDATE_META, compare versionCode, sha256, pick asset |
| `update/GitHubReleaseClient.kt` | REST client + ошибки HTTP |
| `update/ApkVerifier.kt` | SHA-256, package/versionCode/подпись, FileProvider install |
| `update/UpdateViewModel.kt` | check → DownloadManager → verify → install |
| `ui/UpdateScreen.kt` | UI экрана обновлений |
| `MainActivity.kt` | Drawer + навигация |
| `ui/MainScreen.kt` | кнопка ☰ |
| `AndroidManifest.xml` | FileProvider, REQUEST_INSTALL_PACKAGES |
| `res/xml/file_paths.xml` | paths для FileProvider |
| `app/build.gradle.kts` | 1.5 / versionCode 6 |

Погода, валюты, криптовалюты, кеш DataStore — **без функциональных изменений**.

## 3. Результаты тестов

```bash
$ ./build.sh :app:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL in 1m 45s
```

| Класс | Tests | Failures | Errors |
|---|---|---|---|
| WeatherParsingTest | 12 | 0 | 0 |
| CurrencyParsingTest | 13 | 0 | 0 |
| NetworkErrorTest | 14 | 0 | 0 |
| CacheSerializationTest | 5 | 0 | 0 |
| TemperatureFormatTest | 12 | 0 | 0 |
| PlaceLabelTest | 10 | 0 | 0 |
| CryptoParsingTest | 11 | 0 | 0 |
| CryptoFormatTest | 14 | 0 | 0 |
| **UpdateLogicTest** | **16** | 0 | 0 |
| **GitHubReleaseClientTest** | **9** | 0 | 0 |
| **TOTAL** | **116** | **0** | **0** |

Покрытие ТЗ:
- парсинг GitHub API (assets, metadata, flags) ✅  
- сравнение versionCode: равно → latest, локально новее → reject старого, remote новее → update ✅  
- нет metadata / нет APK / prerelease / draft / чужой package → Error ✅  
- HTTP 404/403/429 — маппинг в `ApiError` + тесты сообщений ✅  
- сетевые ошибки → `ApiError.Network` ✅  
- SHA-256 match/mismatch, pick asset ✅  
- повреждённый/несовпадающий APK — логика verify (unit на metadata/sha; полный Android PackageManager — **не на устройстве**) ✅  
- отмена загрузки / установки — системный UI (**не E2E**)  
- регрессии погоды/валют/крипты — все прежние зелёные ✅  
- открытие/закрытие меню, навигация — **код**, не на устройстве  

## 4. Проверка GitHub

```
repo:     HTTP 200, public
latest:   HTTP 404 (нет релизов)
list:     HTTP 200, []
POST release: HTTP 401 Requires authentication
```

**Публикация Release: НЕ ВЫПОЛНЕНА** — нет авторизации (`gh` нет, токена нет). Пароль/токен в отчёте **не запрашивается**.

### Статус публикации
| | |
|---|---|
| Release v1.5 на GitHub | **не создан** |
| APK проверен и готов | **да** |
| Release assets подготовлены | **да** — `/opt/weather-currency/output/release-v1.5/` |

Содержимое `output/release-v1.5/`:
- `WeatherCurrency-v1.5.apk`
- `UPDATE_META.txt`
- `SHA256SUMS.txt`
- `RELEASE_BODY.md` (тело релиза с UPDATE_META)

## 5. Инструкция по публикации (без токена в отчёте)

На машине с доступом к GitHub (не на VPS в открытом виде):

```bash
# 1) Временная авторизация gh (не кладите токен в репозиторий)
gh auth login
# или:
export GITHUB_TOKEN='<fine-grained PAT: contents:write on metanikajkj-netizen/Weather-and-exchange-rates>'

# 2) Публикация стабильного релиза
cd /opt/weather-currency/output/release-v1.5
gh release create v1.5 \
  --repo metanikajkj-netizen/Weather-and-exchange-rates \
  --title "WeatherCurrency 1.5" \
  --notes-file RELEASE_BODY.md \
  WeatherCurrency-v1.5.apk SHA256SUMS.txt UPDATE_META.txt
```

Без `gh` (curl):
```bash
# Create release (draft=false), затем upload assets через
# POST /repos/{owner}/{repo}/releases/{id}/assets?name=...
```

После публикации приложение найдёт релиз через `/releases/latest` и `UPDATE_META`.

### Дальнейшие выпуски обновлений
1. Увеличить `appVersionCode` и `appVersionName` в `app/build.gradle.kts`.
2. `./build.sh :app:assembleDebug` → `output/WeatherCurrency-vX.Y.apk`.
3. `sha256sum` APK.
4. В тело релиза вставить строку:
   ```
   UPDATE_META {"packageName":"com.weathercurrency.app","versionCode":<N>,"versionName":"<X.Y>","apkFileName":"WeatherCurrency-vX.Y.apk","sha256":"<hex>"}
   ```
5. Опубликовать **stable** release (не draft/prerelease) с APK-asset.
6. Не публиковать keystore/пароли.
7. Ключ: тот же `signing/debug.keystore` + бэкап в `/root/weathercurrency-keys-backup/`.

## 6. Сборка APK

```
package: name='com.weathercurrency.app' versionCode='6' versionName='1.5'
minSdkVersion='26' targetSdkVersion='35'
application-label:'Погода и Валюта'
SIGNATURE_VALID
cert SHA-256: 90eaea49f52b8fd11c0cc70df69c88d2f1ba220614fcfbd6e35fc7c4fb42fe54
permissions: INTERNET, ACCESS_NETWORK_STATE, ACCESS_COARSE/FINE_LOCATION,
             REQUEST_INSTALL_PACKAGES (user-initiated OTA only)
```

## 7. Ограничения

1. **GitHub Release не опубликован** (401 без токена) — OTA в продакшене заработает только после публикации по §5.
2. **На реальном Android не проверялось**: Drawer, установка обновления, PackageInstaller, DownloadManager.
3. Полный цикл «скачать → проверить подпись → установить» unit-тестами не покрыт (нужен device/instrumentation).
4. Релиз пока отсутствует → на устройстве «Проверить обновления» покажет ошибку 404 «релизы не найдены» **до** публикации v1.5.
5. APK — debug-сборка с тем же ключом (как все версии проекта).

## 8. Путь и SHA-256 APK

| | |
|---|---|
| Путь | **`/opt/weather-currency/output/WeatherCurrency-v1.5.apk`** |
| На телефоне | `/sdcard/Download/WeatherCurrency-v1.5.apk` |
| SHA-256 | **`ad9ce3efe25a48d1e258b9c812c972dddd5b3562d9a9f3e6d4829a26ec7c5086`** |
| Размер | **12 025 598** bytes |
| versionName / versionCode | **1.5 / 6** |
| `ls -lh` | `-rw-r--r-- … 12M … WeatherCurrency-v1.5.apk` |
| SCP | `scp root@2.27.86.13:/opt/weather-currency/output/WeatherCurrency-v1.5.apk ~/storage/downloads/` |

## 9. Изоляция

```bash
$ find /opt/pump-tracker /root/pump-tracker /opt/vpnautoswitch-ru -xdev -mmin -180
(пусто)
```

---

## ИТОГ

| | |
|---|---|
| BUILD | **SUCCESSFUL** |
| Тесты | **116 / 116 passed** |
| GitHub repo | доступен, public, **0 релизов** |
| Release опубликован | **НЕТ** (нужен токен/gh — инструкция §5) |
| APK | `/opt/weather-currency/output/WeatherCurrency-v1.5.apk` |
| SHA-256 | `ad9ce3efe25a48d1e258b9c812c972dddd5b3562d9a9f3e6d4829a26ec7c5086` |
| Подпись | VALID, поверх 1.4 |
| На реальном Android | **не проверялось** |
