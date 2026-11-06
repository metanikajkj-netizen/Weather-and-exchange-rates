# REPORT — TASK №21 — Публикация WeatherCurrency v2.4 Pre-release

Исполнитель: MiMo Code
Дата: 2026-11-06
Проект: `/opt/weather-currency`
Release: **https://github.com/metanikajkj-netizen/Weather-and-exchange-rates/releases/tag/v2.4**

---

## 0. Подготовка

| Файл | Статус |
|---|---|
| `REPORT_task20.md` | изучен (v2.4/15, SHA, изменения DoH hostname + SERVFAIL) |
| `REPORT_task16.md` | изучен (шаблон публикации v2.3 через VPS + gh) |
| `PROJECT_STATE.md` / `NEXT_SESSION.md` / `DECISIONS.md` | отсутствуют |
| GitHub Releases до публикации | v2.4 → **404**; latest → **v2.2 stable** |
| Пересборка APK | **не выполнялась** (проверка пройдена) |

---

## 1. Проверка APK (до публикации)

Файл: `/opt/weather-currency/output/WeatherCurrency-v2.4.apk`

| Проверка | Результат |
|---|---|
| Существование | ✅ 36 228 316 bytes |
| SHA-256 (ожидаемый) | ✅ **`8499959c902a5133f2be3d1bff962164ebeb505475fe6e7d80c858049cbb1f2a`** |
| versionName | ✅ **2.4** |
| versionCode | ✅ **15** |
| packageName | ✅ `com.weathercurrency.app` |
| Подпись vs v2.3 | ✅ **CERT_MATCH** — `90eaea49f52b8fd11c0cc70df69c88d2f1ba220614fcfbd6e35fc7c4fb42fe54` (CN=WeatherCurrency Debug) |
| `apksigner verify` | ✅ SIGNATURE_VALID |

Ожидаемый SHA-256 **совпал** → публикация без пересборки.

---

## 2. Подготовка публикации

Каталог: `/opt/weather-currency/output/release-v2.4/`

- `WeatherCurrency-v2.4.apk` (36 228 316 B)
- `SHA256SUMS.txt`
- `RELEASE_BODY.md` — описание без учётных данных прокси

Параметры релиза:

| Поле | Значение |
|---|---|
| Tag | **`v2.4`** |
| Название | **`WeatherCurrency v2.4 TEST`** |
| Статус | **Pre-release** (`--prerelease`) |
| Draft | **false** |

Описание: DoH CONNECT по hostname; DNS SERVFAIL при ошибках; улучшенная диагностика; **тестовая версия — Chrome/YouTube ещё не подтверждены**.

Предварительная проверка: `/releases/tags/v2.4` → **404** (тега не было) — публикация с нуля.

---

## 3. Передача на VPS и публикация

```text
SSH root@2.27.86.13 → SSH_OK (gh: metanikajkj-netizen)
rsync --partial → /root/release-v2.4/
  WeatherCurrency-v2.4.apk — 36 228 316 bytes
  SHA256SUMS.txt, RELEASE_BODY.md
VPS: sha256sum -c SHA256SUMS.txt → WeatherCurrency-v2.4.apk: OK
gh release view v2.4 → RELEASE_ABSENT
gh release create v2.4 … --prerelease --title "WeatherCurrency v2.4 TEST" → CREATE_EXIT=0
```

**Не выгружались:** пароли/логины прокси, токены, ключи.

Ссылка: **https://github.com/metanikajkj-netizen/Weather-and-exchange-rates/releases/tag/v2.4**

---

## 4. Результаты после публикации (GitHub API)

### Pre-release v2.4

| Проверка | Результат |
|---|---|
| Release доступен | ✅ `/releases/tags/v2.4` → 200 |
| Название | ✅ `WeatherCurrency v2.4 TEST` |
| **prerelease** | ✅ **true** |
| **draft** | ✅ **false** |
| Asset APK | ✅ `WeatherCurrency-v2.4.apk` — 36 228 316 B |
| Asset SHA256SUMS | ✅ `SHA256SUMS.txt` — 91 B |
| APK скачан с GitHub | ✅ 36 228 316 B |
| SHA-256 скачанного APK | ✅ **`8499959c902a5133f2be3d1bff962164ebeb505475fe6e7d80c858049cbb1f2a`** = ожидаемому |

### Защита стабильного OTA

| Проверка | Результат |
|---|---|
| `GET /releases/latest` | ✅ **tag = `v2.2`**, prerelease = **false**, name = `WeatherCurrency v2.2` |
| v2.4 не стал stable | ✅ prerelease=true → не попадает в latest |
| Настройки OTA приложения | ✅ **не изменялись** |
| Стабильный релиз v2.2 | ✅ **не изменён** |

---

## 5. Итоговая сводка

| | |
|---|---|
| URL | https://github.com/metanikajkj-netizen/Weather-and-exchange-rates/releases/tag/v2.4 |
| Tag / Title | `v2.4` / `WeatherCurrency v2.4 TEST` |
| Pre-release | **true** (draft=false) |
| APK SHA-256 | `8499959c902a5133f2be3d1bff962164ebeb505475fe6e7d80c858049cbb1f2a` |
| Подпись | `90eaea49…` = v2.2/v2.3 |
| OTA `/releases/latest` | **v2.2 stable** — не изменился |
| Пересборка / смена кода | нет |

---

## Ограничения

1. Релиз **тестовый**; работа Chrome/YouTube на устройстве **не подтверждена**.
2. OTA-канал и стабильный v2.2 **не тронуты**.
3. `PROJECT_STATE.md` / `NEXT_SESSION.md` / `DECISIONS.md` отсутствуют.

**После завершения — остановиться.**
