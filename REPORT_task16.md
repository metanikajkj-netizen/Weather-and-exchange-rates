# REPORT — TASK №16 — Публикация тестового APK v2.3 как GitHub Pre-release

Исполнитель: MiMo Code  
Дата: 2026-11-05  
Проект: `/opt/weather-currency`  
Release: **https://github.com/metanikajkj-netizen/Weather-and-exchange-rates/releases/tag/v2.3**

---

## 0. Подготовка

| Файл | Статус |
|---|---|
| `PROJECT_STATE.md` / `NEXT_SESSION.md` / `DECISIONS.md` | **отсутствуют** |
| `REPORT_task13.md`, `REPORT_task14.md`, `REPORT_task15.md` | **изучены** |
| Стабильный релиз | **v2.2** (versionCode 13) |
| Тестовый APK | **v2.3** / versionCode 14 — уже собран в Task15 |
| Пересборка APK | **не выполнялась** |

---

## 1. Проверка готового APK

Файл: `/opt/weather-currency/output/WeatherCurrency-v2.3.apk`

| Проверка | Результат |
|---|---|
| Существование | ✅ `ls` → 36 211 932 bytes |
| SHA-256 (ожидаемый) | ✅ **`2af3a307a4f271125b8ef7769c3c149159f3d166418f32f190c17bc2eb6dea6a`** |
| versionName | ✅ **2.3** |
| versionCode | ✅ **14** |
| packageName | ✅ `com.weathercurrency.app` |
| Сертификат vs v2.2 | ✅ **CERTS_IDENTICAL** (`90eaea49…`) |
| `apksigner verify` | ✅ **SIGNATURE_VALID** |
| Обновлённый VPN-движок в APK | ✅ в `lib/arm64-v8a/libgojni.so`: `[DNS] %s -> DoH via proxy`, `[UDP] %s: dropped (proxy has no UDP support, e.g. QUIC)` |
| Случайные отладочные параметры | ⚠️ badging содержит `application-debuggable` — это **штатная** debug-сборка проекта (так же собран v2.2 stable). Дополнительных debug-переключателей/флагов в UI не добавлено. |

Ожидаемый SHA-256 **совпал** → проверка пройдена, пересборка не понадобилась.

---

## 2. Подготовка публикации

Локальный каталог (запись): `/opt/weather-currency/output/release-v2.3/`
- `WeatherCurrency-v2.3.apk`
- `SHA256SUMS.txt`
- `RELEASE_BODY.md` (без логинов/паролей прокси)

Параметры релиза:
- Tag: **`v2.3`**
- Название: **`WeatherCurrency v2.3 — Proxy VPN DNS Fix (TEST)`**
- Статус: **Pre-release** (`--prerelease`), не draft

Предварительная проверка GitHub: `/releases/tags/v2.3` → **404** (тега не было) — публикация с нуля, без перезаписи.

В описании (`RELEASE_BODY.md`): DNS/DoH через CONNECT, обработка UDP, диагностика, **отсутствие E2E на Android**, ограничения UDP/QUIC и IPv6, назначение — тест Chrome/YouTube/Telegram.

---

## 3. Передача на VPS и публикация

```text
SSH root@2.27.86.13 → SSH_OK (gh: logged in as metanikajkj-netizen)
SCP/rsync release-v2.3 → /root/release-v2.3/
  WeatherCurrency-v2.3.apk — 36 211 932 bytes
sha256sum -c SHA256SUMS.txt → WeatherCurrency-v2.3.apk: OK
gh release view v2.3 → RELEASE_ABSENT
gh release create v2.3 … --prerelease → CREATE_EXIT=0
```

**Не выгружались:** пароли/логины прокси, SSH-ключи, токены.

---

## 4. Результаты после публикации (GitHub API)

### Pre-release v2.3

| Проверка | Результат |
|---|---|
| Релиз существует | ✅ |
| `prerelease` | ✅ **true** |
| `draft` | ✅ **false** |
| Название | ✅ `WeatherCurrency v2.3 — Proxy VPN DNS Fix (TEST)` |
| Вложения | ✅ `WeatherCurrency-v2.3.apk`, `SHA256SUMS.txt` |
| Скачанный APK SHA-256 | ✅ `2af3a307…dea6a` (равен локальному) |
| Размер APK | ✅ **36 211 932** = локальному |

**Ссылка:** https://github.com/metanikajkj-netizen/Weather-and-exchange-rates/releases/tag/v2.3

### Стабильный релиз не изменён

```text
GET /releases/latest → tag=v2.2, prerelease=false, draft=false
```

| Проверка | Результат |
|---|---|
| `/releases/latest` = **v2.2** | ✅ |
| OTA v1.5+ (`releases/latest`) **не предложит** v2.3 как стабильное | ✅ (GitHub отдаёт только non-prerelease latest) |
| v2.2 не удалён/не изменён | ✅ |

---

## 5. Инструкция установки поверх v2.2

1. Скачать **`WeatherCurrency-v2.3.apk`** с  
   https://github.com/metanikajkj-netizen/Weather-and-exchange-rates/releases/tag/v2.3  
   (вложение Assets) — либо `scp`/браузер на устройство в «Загрузки».
2. Открыть APK → «Установить» (**поверх**, без удаления): та же подпись `com.weathercurrency.app`.
3. Разрешить установку из источника, если система спросит.
4. Открыть приложение → меню ☰ → **Proxy VPN**.

Опционально с VPS/ Termux:
```bash
scp root@2.27.86.13:/root/release-v2.3/WeatherCurrency-v2.3.apk ~/storage/downloads/
```

---

## 6. План тестирования VPN на реальном Android

1. Обновить до v2.3 поверх v2.2 (сохраняются настройки прокси и DataStore).
2. Настроить HTTP/HTTPS-прокси → кнопка **«Тест прокси (мимо VPN)»** → успех (это **не** тест TUN).
3. **«Подключить»** → системный диалог VPN → UI: «Подключено» + «Системный VPN: активен».
4. **`adb logcat`**: искать `[DNS]`, `[UDP]`, `[TCP]`, `ProxyVpnService`.
5. **DNS через VPN:** открыть Chrome, зайти на сайты по доменам; ожидание — ответы `[DNS] … -> DoH via proxy` и загрузка страниц.
6. **Chrome** — главные HTTPS-сайты (TCP/443 через CONNECT).
7. **YouTube** — воспроизведение видео (`*.googlevideo.com`).
8. **Telegram** — обычное использование.
9. **QUIC:** если Chrome «висит» — сравнить с `chrome://flags` QUIC off (ожидание: после фикса DNS fallback на TCP работает).
10. **Логи прокси:** CONNECT к `1.1.1.1/8.8.8.8:443` (DoH) + CONNECT к сайтам — подтверждение, что DNS/TCP **через прокси**, не мимо.
11. **IPv6:** при проблемах проверить отсутствие прямых IPv6-соединений мимо VPN.
12. Отключить VPN → baseline сравнение.

Критерий успеха: домены резолвятся при активном VPN; Chrome/YouTube/Telegram работают через прокси; в логах прокси видны DoH и обычные CONNECT.

---

## 7. Ограничения

1. APK **не устанавливался** на телефон в рамках задачи.
2. E2E VPN **не подтверждено** — релиз создан именно для этих тестов.
3. `application-debuggable` — особенность debug-сборки проекта (как у v2.2).
4. Секреты прокси в релиз не включались.

---

## ИТОГ

| | |
|---|---|
| Проверка APK (SHA/version/cert/engine) | ✅ |
| SCP + sha256sum на VPS | ✅ OK |
| GitHub Pre-release v2.3 | ✅ **опубликован** (`prerelease=true`, `draft=false`) |
| Ссылка | https://github.com/metanikajkj-netizen/Weather-and-exchange-rates/releases/tag/v2.3 |
| `/releases/latest` | ✅ **v2.2 stable** (OTA не сломан) |
| v2.2 не изменён | ✅ |
| Установка на телефон | ❌ не выполнялась |
| E2E VPN | ❌ план в §6 |
