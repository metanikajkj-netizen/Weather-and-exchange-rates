# REPORT — TASK №9 — WeatherCurrency v1.8: контрольный релиз OTA (versionCode 9)

Исполнитель: MiMo Code
Дата: 2026-11-05
Проект: `/opt/weather-currency`
APK: `/opt/weather-currency/output/WeatherCurrency-v1.8.apk`

---

## 1. Подготовка

| Проверка | Результат |
|---|---|
| `PROJECT_STATE.md` / `NEXT_SESSION.md` / `DECISIONS.md` | **отсутствуют** |
| `REPORT_task8.md` | изучен (WorkManager, уведомления, диалог, настройки OTA) |
| Версия до задачи | **1.7 / versionCode 8** (на телефоне — 1.7) |
| Целевая версия | **1.8 / versionCode 9** |
| Keystore | **не изменён** (mtime 2026-11-04 21:09); новый сертификат не создавался |
| Среда | Ubuntu proot-distro в Termux (**не** VPS 2.27.86.13) |

## 2. Изменённые файлы

| Файл | Изменение |
|---|---|
| `app/build.gradle.kts` | `appVersionCode = 9`, `appVersionName = "1.8"` |

**Больше исходники не изменялись.**  
Проверка: после `REPORT_task8.md` в `app/src/**/*.kt` файлов с более поздним mtime **нет**.

### Сохранено без изменений (подтверждено отсутствием правок)
- WorkManager и периодическая проверка (~6 ч)
- Системные уведомления и dedup
- Всплывающий диалог обновления
- Настройки auto-check / notify
- OTA-механизм (`GitHubReleaseClient`, `UpdateLogic`, `ApkVerifier`)
- GitHub API URL
- Проверки SHA-256 / packageName / versionCode / сертификата
- Погода, валюты, криптовалюты, интерфейс, навигация
- packageName `com.weathercurrency.app`
- Конфигурация signing (`signing/debug.keystore`, aliases/passwords)

## 3. Сборка и тесты

```bash
$ ./build.sh :app:assembleDebug
BUILD SUCCESSFUL in 1m 27s

$ ./build.sh :app:testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL in 2m 27s
```

| | |
|---|---|
| BUILD | **SUCCESSFUL** |
| Тесты | **134 / 134 passed**, failures=0, errors=0 |

Состав: Weather 12, Currency 13, Network 14, Cache 5, Temperature 12, PlaceLabel 10, CryptoParse 11, CryptoFormat 14, UpdateLogic 16, GitHubRelease 9, **UpdateAutoLogic 18**.

## 4. Параметры APK

```text
package: name='com.weathercurrency.app' versionCode='9' versionName='1.8'
application-label:'Погода и Валюта'
```

| Параметр | Значение |
|---|---|
| applicationId | `com.weathercurrency.app` |
| versionName | **1.8** |
| versionCode | **9** |
| Подпись | **SIGNATURE_VALID** |
| Размер | **12 504 037** bytes (`ls -lh` → 12M) |
| SHA-256 | **`5cad133581057d01bf06f05de088007ce78ea8648d2e3dd4347711362963d7f2`** |
| Путь | `/opt/weather-currency/output/WeatherCurrency-v1.8.apk` |

## 5. Сертификат v1.7 ↔ v1.8

```text
v1.7 Signer DN: CN=WeatherCurrency Debug, OU=WeatherCurrency, O=WeatherCurrency, L=NA, C=RU
v1.7 SHA-256: 90eaea49f52b8fd11c0cc70df69c88d2f1ba220614fcfbd6e35fc7c4fb42fe54

v1.8 Signer DN: CN=WeatherCurrency Debug, OU=WeatherCurrency, O=WeatherCurrency, L=NA, C=RU
v1.8 SHA-256: 90eaea49f52b8fd11c0cc70df69c88d2f1ba220614fcfbd6e35fc7c4fb42fe54

diff → CERTS_IDENTICAL_V17_V18
```

## 6. Совместимость обновления поверх v1.7

| Условие | Значение |
|---|---|
| applicationId | `com.weathercurrency.app` = `com.weathercurrency.app` |
| versionCode | **9 > 8** (v1.7) |
| Подпись | идентична |

⇒ **v1.8 устанавливается поверх v1.7 без удаления приложения.**  
Установка на устройство **не выполнялась** (ограничение).

## 7. Файлы GitHub Release

Каталог: **`/opt/weather-currency/output/release-v1.8/`**

| Файл | Наличие |
|---|---|
| `WeatherCurrency-v1.8.apk` | да (12 504 037 bytes) |
| `SHA256SUMS.txt` | да — `5cad1335…d7f2  WeatherCurrency-v1.8.apk` |
| `UPDATE_META.txt` | да |
| `RELEASE_BODY.md` | да (описание + UPDATE_META в конце) |

### UPDATE_META (совместим с OTA-клиентом v1.7)

```
UPDATE_META {"packageName":"com.weathercurrency.app","versionCode":9,"versionName":"1.8","apkFileName":"WeatherCurrency-v1.8.apk","sha256":"5cad133581057d01bf06f05de088007ce78ea8648d2e3dd4347711362963d7f2"}
```

Проверки формата (`META_OK`):
- префикс `UPDATE_META `, валидный JSON  
- `versionCode=9`, `versionName=1.8`, `packageName=com.weathercurrency.app`  
- `apkFileName=WeatherCurrency-v1.8.apk`  
- `sha256` — 64 hex, совпадает с файлом APK  
- та же строка в конце `RELEASE_BODY.md`  

Описание релиза в `RELEASE_BODY.md`:  
«WeatherCurrency v1.8 — проверочный релиз для тестирования автоматического обнаружения обновлений. Функциональность приложения не изменена.»

## 8. Отсутствие функциональных изменений

- Изменён **только** `versionName`/`versionCode` в `app/build.gradle.kts`.
- Исходники `app/src` после Task8 **не правились**.
- Логика WorkManager / уведомлений / диалога / OTA **не менялась** (не ускорялась и не «оптимизировалась»).
- Настройки и данные пользователя (DataStore) не трогались.
- Старые релизы в `output/` **не удалялись**.

## 9. Ограничения

1. APK на телефон **не устанавливался**.  
2. GitHub Release **не опубликован** — ждём указаний.  
3. Фоновое обнаружение 1.8 из установленной 1.7 будет проверено **после** публикации релиза и распределения APK.  
4. Сборка debug с существующим stable-keystore (как все версии проекта).

## 10. Изоляция

```bash
$ find /opt/pump-tracker /root/pump-tracker /opt/vpnautoswitch-ru -xdev -mmin -120
(пусто)
```

Сторонние проекты **не изменялись**.

---

## ИТОГ

| | |
|---|---|
| BUILD | **SUCCESSFUL** |
| Тесты | **134 / 134 passed** |
| versionName / versionCode | **1.8 / 9** ✅ |
| Сертификат v1.7 == v1.8 | **CERTS_IDENTICAL** (`90eaea49…`) ✅ |
| Поверх v1.7 | **да** (8→9, same package, same cert) |
| SHA-256 | `5cad133581057d01bf06f05de088007ce78ea8648d2e3dd4347711362963d7f2` |
| Размер | 12 504 037 bytes |
| Release files | `/opt/weather-currency/output/release-v1.8/` (4 файла) ✅ |
| Функциональные изменения | **нет** ✅ |
| Публикация | **не выполнена** — остановился, жду указаний |
