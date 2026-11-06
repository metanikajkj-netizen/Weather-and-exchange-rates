# REPORT — TASK №24 — Публикация WeatherCurrency v2.5 TEST

Исполнитель: MiMo Code
Дата: 2026-11-06
Проект: `/opt/weather-currency`
Release: **https://github.com/metanikajkj-netizen/Weather-and-exchange-rates/releases/tag/v2.5**

---

## 0. Подготовка

| Файл | Статус |
|---|---|
| `REPORT_task23.md` | изучен (v2.5/16, AAAA-фильтр, fast-close IPv6 TCP) |
| `REPORT_task21.md` | изучен (шаблон публикации v2.4) |
| `REPORT_task22.md` | изучен (LIVE-причина: IPv6 через CONNECT → TLS EOF) |
| `PROJECT_STATE.md` / `NEXT_SESSION.md` / `DECISIONS.md` | **отсутствуют** |
| GitHub до публикации | v2.5 → **404**; latest → **v2.2 stable** |
| Пересборка APK / изменения кода | **не выполнялись** |

---

## 1. Проверка APK (до публикации)

Файл: `/opt/weather-currency/output/WeatherCurrency-v2.5.apk`

| Проверка | Результат |
|---|---|
| Существование | ✅ 36 424 924 bytes |
| SHA-256 (ожидаемый) | ✅ **`fc628a4158618001e524258a66599a581aa996ef924a7d8d758f65dd1a9d5625`** |
| versionName | ✅ **2.5** |
| versionCode | ✅ **16** |
| packageName | ✅ `com.weathercurrency.app` |
| Подпись vs v2.4 | ✅ **CERT_MATCH** — `90eaea49f52b8fd11c0cc70df69c88d2f1ba220614fcfbd6e35fc7c4fb42fe54` (CN=WeatherCurrency Debug) |
| `apksigner verify` | ✅ SIGNATURE_VALID |

Ожидаемый SHA-256 **совпал** → публикация без пересборки.

---

## 2. Подготовка публикации

Каталог: `/opt/weather-currency/output/release-v2.5/`

- `WeatherCurrency-v2.5.apk` (36 424 924 B)
- `SHA256SUMS.txt`
- `RELEASE_BODY.md` — описание без учётных данных прокси

| Поле | Значение |
|---|---|
| Tag | **`v2.5`** |
| Название | **`WeatherCurrency v2.5 TEST`** |
| Pre-release | **true** |
| Draft | **false** |

Описание релиза: исправлена обработка IPv6 DNS-запросов; фильтрация AAAA; быстрое закрытие неподдерживаемых IPv6 TCP; IPv6 остаётся внутри VPN; **работа Chrome и YouTube ожидает проверки на устройстве**.

Предварительная проверка: `/releases/tags/v2.5` → **404** — публикация с нуля.

---

## 3. Передача на VPS и публикация

```text
SSH root@2.27.86.13 → SSH_OK (gh: metanikajkj-netizen)
mkdir /root/release-v2.5
rsync --partial → WeatherCurrency-v2.5.apk, SHA256SUMS.txt, RELEASE_BODY.md
VPS: sha256sum -c SHA256SUMS.txt → WeatherCurrency-v2.5.apk: OK
gh release view v2.5 → RELEASE_ABSENT
gh release create v2.5 … --prerelease --title "WeatherCurrency v2.5 TEST" → CREATE_EXIT=0
```

**Не выгружались:** пароли/логины прокси, токены, ключи.

---

## 4. Результаты после публикации (GitHub API)

### Pre-release v2.5

| Проверка | Результат |
|---|---|
| Release доступен | ✅ `/releases/tags/v2.5` → 200 |
| Название | ✅ `WeatherCurrency v2.5 TEST` |
| **prerelease** | ✅ **true** |
| **draft** | ✅ **false** |
| Asset APK | ✅ `WeatherCurrency-v2.5.apk` — 36 424 924 B |
| Asset SHA256SUMS | ✅ `SHA256SUMS.txt` — 91 B |
| APK скачан с GitHub | ✅ 36 424 924 B |
| SHA-256 скачанного APK | ✅ **`fc628a4158618001e524258a66599a581aa996ef924a7d8d758f65dd1a9d5625`** = ожидаемому |
| SHA256SUMS (asset) | ✅ та же сумма |

### Защита стабильной OTA

| Проверка | Результат |
|---|---|
| `GET /releases/latest` | ✅ **tag = `v2.2`**, prerelease = **false**, name = `WeatherCurrency v2.2` |
| v2.5 не стал stable | ✅ prerelease=true → не попадает в latest |
| Настройки OTA приложения | ✅ **не изменялись** |
| Стабильный релиз v2.2 | ✅ **не изменён** |

---

## 5. Итоговая сводка

| | |
|---|---|
| URL | https://github.com/metanikajkj-netizen/Weather-and-exchange-rates/releases/tag/v2.5 |
| Tag / Title | `v2.5` / `WeatherCurrency v2.5 TEST` |
| Pre-release | **true** (draft=false) |
| APK SHA-256 | `fc628a4158618001e524258a66599a581aa996ef924a7d8d758f65dd1a9d5625` |
| Подпись | `90eaea49…` = v2.2–v2.4 |
| OTA `/releases/latest` | **v2.2 stable** — не изменился |
| Пересборка / смена кода | нет |

---

## Ограничения

1. Релиз **тестовый**; E2E Chrome/YouTube на устройстве с v2.5 **не проводился**.
2. OTA-канал и стабильный v2.2 **не тронуты**.
3. `PROJECT_STATE.md` / `NEXT_SESSION.md` / `DECISIONS.md` отсутствуют.

**После завершения — остановиться.**
