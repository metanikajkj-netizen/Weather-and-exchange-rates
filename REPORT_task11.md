# REPORT — TASK №11 — WeatherCurrency v2.0: сборка и публикация GitHub Release

Исполнитель: MiMo Code
Дата: 2026-11-05
Проект: `/opt/weather-currency`
APK (локальный): `/opt/weather-currency/output/WeatherCurrency-v2.0.apk`
GitHub: https://github.com/metanikajkj-netizen/Weather-and-exchange-rates

---

## 1. Подготовка и документация

| Файл | Статус |
|---|---|
| `PROJECT_STATE.md` | **отсутствует** |
| `NEXT_SESSION.md` | **отсутствует** |
| `DECISIONS.md` | **отсутствует** |
| `REPORT_task10.md` | **прочитан** (v1.9, диагностический 60s, WorkManager 6ч, OTA) |

Среда: Ubuntu proot-distro в Termux (не VPS).  
VPS из задания: `root@2.27.86.13`.  
Текущая версия до задачи: **1.9 / versionCode 10**.  
Цель: **2.0 / versionCode 11** + полный цикл публикации.

## 2. Изменённые файлы

| Файл | Изменение |
|---|---|
| `app/build.gradle.kts` | `appVersionCode = 11`, `appVersionName = "2.0"` |

**Больше исходники не изменялись.**  
Сохранены: WorkManager 6ч, диагностический 60s, OneTimeWork, уведомления, диалог, настройки, OTA-клиент GitHub, SHA-256/подпись, погода/валюты/крипта, packageName, keystore, GitHub URL.

## 3. Сборка и тесты

```bash
$ ./build.sh :app:assembleDebug
BUILD SUCCESSFUL

$ ./build.sh :app:testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL in 1m 24s
```

| | |
|---|---|
| BUILD | **SUCCESSFUL** |
| Тесты | **144 / 144 passed**, failures=0, errors=0 |

## 4. Параметры APK

| Параметр | Значение |
|---|---|
| package | `com.weathercurrency.app` |
| versionName | **2.0** |
| versionCode | **11** |
| Подпись | **SIGNATURE_VALID** |
| Размер | **12 520 421** bytes |
| SHA-256 | **`90583739892800ed14fdab8271b4ea7948058ced2c82246aa438a7d71581f175`** |
| Путь | `/opt/weather-currency/output/WeatherCurrency-v2.0.apk` |

### Сертификат v1.9 ↔ v2.0

```text
v1.9 SHA-256 cert: 90eaea49f52b8fd11c0cc70df69c88d2f1ba220614fcfbd6e35fc7c4fb42fe54
v2.0 SHA-256 cert: 90eaea49f52b8fd11c0cc70df69c88d2f1ba220614fcfbd6e35fc7c4fb42fe54
diff → CERTS_IDENTICAL_V19_V20
```

### Установка поверх v1.9

| Условие | Значение |
|---|---|
| applicationId | `com.weathercurrency.app` |
| versionCode | **11 > 10** |
| Подпись | идентична |

⇒ совместима с установкой поверх v1.9 (на телефон **не устанавливалась**).

## 5. Подготовленные файлы релиза

Каталог: **`/opt/weather-currency/output/release-v2.0/`**

| Файл | Наличие |
|---|---|
| `WeatherCurrency-v2.0.apk` | да (12 520 421 bytes) |
| `SHA256SUMS.txt` | да — `90583739…f175  WeatherCurrency-v2.0.apk` |
| `UPDATE_META.txt` | да |
| `RELEASE_BODY.md` | да (описание + UPDATE_META в конце) |

### UPDATE_META

```
UPDATE_META {"packageName":"com.weathercurrency.app","versionCode":11,"versionName":"2.0","apkFileName":"WeatherCurrency-v2.0.apk","sha256":"90583739892800ed14fdab8271b4ea7948058ced2c82246aa438a7d71581f175"}
```

Формат проверен (`META_OK`): JSON, versionCode=11, versionName=2.0, sha256=64 hex = реальный APK, строка в конце `RELEASE_BODY.md`.  
Описание: «WeatherCurrency v2.0 — контрольная версия для тестирования автоматического обнаружения обновлений в v1.9. Функциональность приложения не изменена.»

## 6. SSH / SCP — **ВЫПОЛНЕНО (после исправления доступа)**

### 6.1 SSH (продолжение TASK №11)

Пользователь добавил публичный ключ `/root/.ssh/id_ed25519.pub` в `/root/.ssh/authorized_keys` на VPS.

```bash
$ ssh -o BatchMode=yes -i /root/.ssh/id_ed25519 root@2.27.86.13 'echo SSH_OK'
SSH_OK
VM-301554
```

`gh` на VPS: **Logged in to github.com as metanikajkj-netizen**.

Ранее (до исправления): `Permission denied (publickey,password)` — зафиксировано в первой версии отчёта.

### 6.2 SCP release → VPS

```bash
$ scp -o BatchMode=yes -i /root/.ssh/id_ed25519 -r \
    /opt/weather-currency/output/release-v2.0 \
    root@2.27.86.13:/root/release-v2.0
SCP_OK
```

На VPS: `/root/release-v2.0/` — 4 файла (APK 12 520 421 bytes + SHA256SUMS + UPDATE_META + RELEASE_BODY).

### 6.3 SHA-256 на VPS

```bash
$ cd /root/release-v2.0 && sha256sum -c SHA256SUMS.txt
WeatherCurrency-v2.0.apk: OK
```

Ожидаемый хеш: `90583739892800ed14fdab8271b4ea7948058ced2c82246aa438a7d71581f175` — **совпал**.

## 7. Публикация GitHub Release

### 7.1 Проверка перед публикацией
```text
gh release view v2.0 → release not found  (RELEASE_ABSENT)
```

### 7.2 Публикация (на VPS, через gh)

```bash
gh release create v2.0 \
  WeatherCurrency-v2.0.apk \
  SHA256SUMS.txt \
  UPDATE_META.txt \
  --repo metanikajkj-netizen/Weather-and-exchange-rates \
  --target main \
  --title "WeatherCurrency v2.0" \
  --notes-file RELEASE_BODY.md

→ https://github.com/metanikajkj-netizen/Weather-and-exchange-rates/releases/tag/v2.0
CREATE_EXIT=0
```

Релиз до публикации **не существовал** — создавался заново, ничего не перезаписывалось.

### 7.3 Проверки после публикации

| Проверка | Результат |
|---|---|
| Release v2.0 существует | ✅ |
| draft / prerelease | ✅ `false` / `false` (опубликован, stable) |
| Вложения (3) | ✅ `WeatherCurrency-v2.0.apk`, `SHA256SUMS.txt`, `UPDATE_META.txt` |
| SHA-256 скачанного APK | ✅ `90583739892800ed14fdab8271b4ea7948058ced2c82246aa438a7d71581f175` (= локальному) |
| SHA256SUMS.txt в релизе | ✅ тот же хеш |
| UPDATE_META в теле | ✅ versionCode=**11**, versionName=**2.0** |
| GitHub API `/releases/latest` | ✅ **tag=v2.0** |
| versionCode опубликованного релиза | ✅ **11** |

Прямая ссылка:  
**https://github.com/metanikajkj-netizen/Weather-and-exchange-rates/releases/tag/v2.0**

Все проверки §7.3 пройдены — публикация **полностью успешна**.

## 8. Ограничения

1. APK на телефон **не устанавливался** — на устройстве должна остаться **v1.9** (чтобы сама обнаружила v2.0).  
2. Функциональность не менялась (только versionName/versionCode).  
3. Сторонние проекты и keystore не трогались.  
4. Исходники после сборки не изменялись; пересборка не выполнялась.

## 9. Изоляция

```bash
$ find /opt/pump-tracker /root/pump-tracker /opt/vpnautoswitch-ru -xdev -mmin -180
(пусто)
```

---

## ИТОГ (этапы) — **ПОЛНЫЙ УСПЕХ**

| Этап | Статус |
|---|---|
| versionName/versionCode = 2.0 / 11 | ✅ |
| BUILD SUCCESSFUL | ✅ |
| Тесты 144/144 | ✅ |
| Package / подпись / cert = v1.9 | ✅ CERTS_IDENTICAL |
| SHA-256 / размер | ✅ `90583739…f175` / 12 520 421 |
| Файлы `output/release-v2.0/` | ✅ |
| SSH `SSH_OK` | ✅ (после добавления ключа пользователем) |
| SCP → `/root/release-v2.0/` | ✅ SCP_OK |
| `sha256sum -c` на VPS | ✅ `WeatherCurrency-v2.0.apk: OK` |
| Публикация GitHub Release v2.0 | ✅ **опубликован**, not draft/prerelease |
| 3 вложения + UPDATE_META | ✅ |
| SHA-256 опубликованного APK | ✅ совпадает |
| `/releases/latest` → v2.0, versionCode 11 | ✅ |
| Телефон остался на v1.9 | ✅ (APK не ставился) |

**Цель TASK №11 достигнута:** GitHub Release v2.0 опубликован и проверен; на телефон APK не устанавливался.
