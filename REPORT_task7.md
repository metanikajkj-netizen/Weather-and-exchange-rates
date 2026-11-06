# REPORT — TASK №7 — WeatherCurrency v1.6: подготовка OTA-тестирования

Исполнитель: MiMo Code
Дата: 2026-11-05
Проект: `/opt/weather-currency`
APK: `/opt/weather-currency/output/WeatherCurrency-v1.6.apk`
GitHub: https://github.com/metanikajkj-netizen/Weather-and-exchange-rates

---

## 1. Подготовка

| Проверка | Результат |
|---|---|
| Проект `/opt/weather-currency` | существует, v1.5 / versionCode 6 |
| `PROJECT_STATE.md`, `NEXT_SESSION.md`, `DECISIONS.md` | **отсутствуют** |
| `REPORT_task6.md` | изучен (Drawer, OTA, UPDATE_META, FileProvider) |
| Релиз v1.5 на GitHub | **опубликован**: tag `v1.5`, assets `WeatherCurrency-v1.5.apk`, `UPDATE_META.txt`, `SHA256SUMS.txt` |
| `/releases/latest` | HTTP **200**, `versionCode:6` в body |
| Keystore | `/opt/weather-currency/signing/debug.keystore` — **не изменён** (mtime 2026-11-04 21:09) |
| Бэкап ключа | `/root/weathercurrency-keys-backup/debug.keystore` — не трогали |

Функциональность (погода, валюты, крипта, UI, навигация, OTA-механизм, URL GitHub API) — **не менялась**.

## 2. Изменённые файлы

| Файл | Изменение |
|---|---|
| `app/build.gradle.kts` | `appVersionCode = 7`, `appVersionName = "1.6"` |

Больше исходники **не изменялись**. Новый keystore **не создавался**. packageName **не менялся**.

## 3. Результаты тестов

```bash
$ ./build.sh :app:testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL in 1m 31s
```

| | |
|---|---|
| Всего тестов | **116** |
| Failures | **0** |
| Errors | **0** |

Состав (без регрессий): Weather 12, Currency 13, Network 14, Cache 5, Temperature 12, PlaceLabel 10, CryptoParse 11, CryptoFormat 14, UpdateLogic 16, GitHubRelease 9.

## 4. Параметры APK

```bash
$ aapt2 dump badging WeatherCurrency-v1.6.apk | head -1
package: name='com.weathercurrency.app' versionCode='7' versionName='1.6' …
application-label:'Погода и Валюта'
```

| Параметр | Значение |
|---|---|
| package / applicationId | `com.weathercurrency.app` |
| versionName | **1.6** |
| versionCode | **7** |
| minSdk / targetSdk | 26 / 35 |
| BUILD | **SUCCESSFUL** |
| `apksigner verify` | **SIGNATURE_VALID** |
| Размер | **12 025 598** bytes (`ls -lh` → `12M`) |
| SHA-256 | **`151af71e7ff533cde5b1f1abf73856f96894969f2c6dea42aaa98b278068afeb`** |
| Путь | `/opt/weather-currency/output/WeatherCurrency-v1.6.apk` |

## 5. Сравнение сертификатов подписей v1.5 ↔ v1.6

```text
v1.5 Signer #1 DN: CN=WeatherCurrency Debug, OU=WeatherCurrency, O=WeatherCurrency, L=NA, C=RU
v1.5 SHA-256: 90eaea49f52b8fd11c0cc70df69c88d2f1ba220614fcfbd6e35fc7c4fb42fe54

v1.6 Signer #1 DN: CN=WeatherCurrency Debug, OU=WeatherCurrency, O=WeatherCurrency, L=NA, C=RU
v1.6 SHA-256: 90eaea49f52b8fd11c0cc70df69c88d2f1ba220614fcfbd6e35fc7c4fb42fe54

$ diff сертификатов → пусто → CERTS_IDENTICAL
$ apksigner verify → V15_SIGNATURE_VALID, V16_SIGNATURE_VALID
```

**Сертификаты полностью совпадают** — тот же keystore, что у v1.5 (и всех 1.0–1.5).

## 6. Установка поверх v1.5 (без удаления)

| Условие Android upgrade | Статус |
|---|---|
| Одинаковый `applicationId` | `com.weathercurrency.app` = `com.weathercurrency.app` ✅ |
| `versionCode` v1.6 > v1.5 | **7 > 6** ✅ |
| Одинаковая подпись | cert SHA-256 идентичны ✅ |

⇒ **v1.6 устанавливается поверх v1.5 без удаления приложения** (сохраняются данные DataStore).  
Установка на реальное устройство в этой задаче **не выполнялась** (по ограничению).

## 7. Файлы для GitHub Release v1.6

Каталог: **`/opt/weather-currency/output/release-v1.6/`**

| Файл | Описание |
|---|---|
| `WeatherCurrency-v1.6.apk` | подписанный APK (12 025 598 bytes) |
| `UPDATE_META.txt` | машинная строка UPDATE_META |
| `SHA256SUMS.txt` | `151af71e…8afeb  WeatherCurrency-v1.6.apk` |
| `RELEASE_BODY.md` | тело релиза **с UPDATE_META** (совместимо с парсером v1.5) |

### UPDATE_META (проверено парсером-совместимым форматом)

```
UPDATE_META {"packageName":"com.weathercurrency.app","versionCode":7,"versionName":"1.6","apkFileName":"WeatherCurrency-v1.6.apk","sha256":"151af71e7ff533cde5b1f1abf73856f96894969f2c6dea42aaa98b278068afeb"}
```

Проверки формата (python):
- префикс `UPDATE_META ` ✅  
- JSON валиден; `versionCode=7`, `versionName=1.6`, `packageName=com.weathercurrency.app` ✅  
- `apkFileName=WeatherCurrency-v1.6.apk` ✅  
- `sha256` — 64 hex, совпадает с файлом APK ✅  
- та же строка присутствует в `RELEASE_BODY.md` ✅  

Формат идентичен принятому в Task6 и опубликованному релизу v1.5 — приложение v1.5 распознает метаданные и предложит обновление (7 > 6).

## 8. Готовность к публикации на GitHub

| | |
|---|---|
| APK собран и подписан | **да** |
| SHA-256 / versionCode / package | **да** |
| Release assets подготовлены | **да** — `output/release-v1.6/` |
| UPDATE_META совместим с парсером v1.5 | **да** |
| **Публикация релиза** | **НЕ ВЫПОЛНЕНА** (по ограничению задачи) |

Публикацию выполнить позже по указанию, например:

```bash
cd /opt/weather-currency/output/release-v1.6
gh release create v1.6 \
  --repo metanikajkj-netizen/Weather-and-exchange-rates \
  --title "WeatherCurrency v1.6" \
  --notes-file RELEASE_BODY.md \
  WeatherCurrency-v1.6.apk UPDATE_META.txt SHA256SUMS.txt
```

## 9. Ограничения

1. APK на телефон **не устанавливался**; OTA-цикл v1.5 → v1.6 на устройстве **не проверялся**.
2. GitHub Release v1.6 **не опубликован** — ждём дальнейших указаний.
3. `PROJECT_STATE.md` / `NEXT_SESSION.md` / `DECISIONS.md` отсутствуют в проекте.
4. Сборка debug (как все версии проекта) с тем же stable-keystore.

## 10. Изоляция

```bash
$ find /opt/pump-tracker /root/pump-tracker /opt/vpnautoswitch-ru -xdev -mmin -120
(пусто)
```

Другие проекты VPS **не изменялись**; keystore **не перезаписывался**.

---

## ИТОГ

| | |
|---|---|
| BUILD | **SUCCESSFUL** |
| Тесты | **116 / 116 passed** |
| versionName / versionCode | **1.6 / 7** |
| Подпись v1.5 == v1.6 | **CERTS_IDENTICAL** (`90eaea49…`) |
| Установка поверх 1.5 | **да** (same package + VC 7>6 + same cert) |
| APK | `/opt/weather-currency/output/WeatherCurrency-v1.6.apk` |
| SHA-256 | `151af71e7ff533cde5b1f1abf73856f96894969f2c6dea42aaa98b278068afeb` |
| Размер | 12 025 598 bytes |
| Release assets | `/opt/weather-currency/output/release-v1.6/` |
| Публикация | **ожидает указаний** |
