# REPORT — TASK №20 — WeatherCurrency v2.4 TEST: исправление DNS-over-HTTPS

Исполнитель: MiMo Code
Дата: 2026-11-06
Проект: `/opt/weather-currency`
Версия до: **2.3 / versionCode 14** → **2.4 / versionCode 15**
packageName: `com.weathercurrency.app` (не изменён)

---

## 0. Документация

| Файл | Статус |
|---|---|
| `PROJECT_STATE.md` / `NEXT_SESSION.md` / `DECISIONS.md` | **отсутствуют** (не созданы) |
| `REPORT_task19.md` | изучен (F1: нет SERVFAIL; F3: DoH CONNECT по IP) |
| `REPORT_task15.md`, `REPORT_task17.md` | изучены |

---

## 1. Изменённые файлы

| Файл | Изменение |
|---|---|
| **`/opt/tun2socks/tunnel/dns.go`** | DoH CONNECT по **hostname**; **SERVFAIL** при отказе обоих upstream; диагностика; `WeatherCurrency/2.4` |
| **`/opt/tun2socks/metadata/metadata.go`** | Поле `Host` + `ConnectAddress()` (hostname при CONNECT, иначе IP:port — прежнее поведение) |
| **`/opt/tun2socks/proxy/http/http.go`** | `shakeHand` использует `metadata.ConnectAddress()` вместо `DestinationAddress()` |
| **`/opt/tun2socks/tunnel/dns_test.go`** | **новый** — unit-тесты SERVFAIL и ConnectAddress |
| **`app/.../ProxyVpnService.kt`** | `setLogLevel("warn")` → **`setLogLevel("info")`** (DNS-диагностика не должна скрываться) |
| **`app/build.gradle.kts`** | `versionName=2.4`, `versionCode=15` |
| `app/libs/tun2socks.aar` | пересобран (`gomobile bind`) |
| `output/WeatherCurrency-v2.4.apk` | **новый** тестовый APK |

**Не менялось:** TCP-relay, UDP/QUIC fail-fast, маршруты/DNS VpnService, keystore, OTA, packageName.

---

## 2. Причины исправлений (из TASK19)

| ID | Проблема | Исправление |
|---|---|---|
| **F3** | DoH-CONNECT к сырым IP `1.1.1.1:443` / `8.8.8.8:443` — прокси может резать IP-CONNECT («Тест прокси» идёт по имени) | CONNECT **`cloudflare-dns.com:443`** и **`dns.google:443`** |
| **F1** | При отказе DoH — `continue` **без ответа** → Chrome висит до своего таймаута | Клиенту отправляется **SERVFAIL** (rcode=2) с сохранением txid и вопроса |
| **F5** | `setLogLevel("warn")` скрывал `Infof`-события DNS | Уровень **`info`**; ключевые события — `Warnf` (SERVFAIL, ошибки) |
| цикл DNS | Риск локального резолва имени DoH | Имя **не резолвится локально**: `Host` уходит в CONNECT, **прокси** резолвит; в `dns.go` **нет** `LookupIP`/`Resolve*` |

---

## 3. Что сделано в коде

### 3.1 Hostname CONNECT (`metadata.go` + `http.go` + `dns.go`)

```go
// metadata.ConnectAddress()
if m.Host != "" {
    return net.JoinHostPort(m.Host, fmt.Sprintf("%d", m.DstPort)) // cloudflare-dns.com:443
}
return m.DestinationAddress() // прежний IP:port для обычного TCP
```

- `dohOnce` создаёт `Metadata{Host: "cloudflare-dns.com"|"dns.google", DstPort: 443}`.
- `shakeHand` шлёт `CONNECT cloudflare-dns.com:443 HTTP/1.1`.
- TLS: `ServerName` = то же имя, `MinVersion` TLS1.2, **без** `InsecureSkipVerify` (проверка сертификатов сохранена).
- HTTP: `Host` + URL `https://cloudflare-dns.com/dns-query` (SNI/Host согласованы).
- Отказ прокси (не поддерживает hostname-CONNECT) → ошибка `connect via proxy` → следующий upstream → **SERVFAIL** (не зависание).

### 3.2 SERVFAIL (`buildSERVFAIL` в `dns.go`)

При **полном** отказе обоих DoH-серверов:

1. Копируется **transaction ID** запроса.
2. Флаги: **QR=1**, Opcode/RD из запроса, **RA=1**, **RCODE=2 (SERVFAIL)**.
3. QDCOUNT сохранён; ANCOUNT/NSCOUNT/ARCOUNT = 0; секция вопроса скопирована.
4. Ответ пишется тем же `uc.WriteTo(resp, from)` — IP/UDP-адреса и порты TUN-flow сохраняются (checksum считает gvisor).
5. Лог: `[DNS] SERVFAIL -> <dest>`.

Ложный NOERROR **не** возвращается.

### 3.3 Диагностика (без секретов)

| Событие | Уровень | Видно при `info` |
|---|---|---|
| `[DNS] %s -> DoH via proxy (hostname CONNECT)` | Info | ✅ |
| `[DNS] DoH ok for %s (%d bytes)` | Info | ✅ |
| `[DNS] DoH %s failed: %v` (connect/tls/timeout/http) | Warn | ✅ |
| `[DNS] SERVFAIL -> %s` | Warn | ✅ |
| `[DNS] write response` / `write SERVFAIL` | Warn | ✅ |

Не логируются: пароли, `Proxy-Authorization`, содержимое DNS-запросов/ответов.

---

## 4. Результаты тестов

### 4.1 Go (tun2socks)

```
go build ./...          → ok
go test ./... -count=1  → ok:
  buffer/allocator, internal/pool, socks4, socks5,
  tunnel (dns_test: SERVFAIL + ConnectAddress) — PASS
```

Покрытие `dns_test.go`:

- `TestBuildSERVFAILPreservesIDAndQuestion` — txid `0x1234`, QR, rcode=2, QDCOUNT, пустые AN/NS/AR, вопрос `example.com`, RD.
- `TestBuildSERVFAILShortQuery` — nil для <12 байт.
- `TestBuildSERVFAILNoQuestion` — заголовок-only, rcode=2.
- `TestConnectAddressHostname` — `cloudflare-dns.com:443`.
- `TestConnectAddressIPFallback` — `1.1.1.1:443`; Host перекрывает IP.

### 4.2 Kotlin (app)

```
./build.sh :app:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL
tests=157 failures=0 (14 классов)
```

### 4.3 Интеграция (mock CONNECT :18080, hostname-путь)

| Сценарий | Результат |
|---|---|
| CONNECT `cloudflare-dns.com:443` + TLS + POST DoH | **OK** rcode=0, an=2 |
| CONNECT `dns.google:443` + TLS + POST DoH | **OK** rcode=0, an=6 |
| CONNECT `www.gstatic.com:443` + TLS + GET (совместимость TCP) | **OK** `HTTP/1.1 204` |
| Локальный DNS-резолв в `dns.go` | **отсутствует** (`NO_LOCAL_DNS_LOOKUP`) |
| Прямой DNS вне прокси | **нет** — только `Proxy().DialContext` |

**Классификация:** DoH/TCP — mock CONNECT; локальные DNS-резолвы — не выполнялись; **реальный прокси и Android E2E** — не выполнялись (§8).

---

## 5. Проверка DNS (сводка)

| Проверка | Результат |
|---|---|
| CONNECT по hostname | ✅ код + интеграционный тест |
| TLS-проверка сертификатов | ✅ ServerName, без InsecureSkipVerify |
| DNS A/AAAA | ✅ wire-format DoH (тесты A; AAAA — семантика upstream) |
| Успешный DoH | ✅ лог `DoH ok` + интеграция rcode=0 |
| Отказ обоих DoH | ✅ → SERVFAIL (unit-тест + код) |
| SERVFAIL: txid/адреса/порты | ✅ txid+вопрос копируются; `WriteTo(from)` — тот же flow |
| Параллельные DNS-запросы | ✅ по flow — отдельные goroutine (как v2.3) |
| DNS-утечки | ✅ без локального Lookup; DoH только через прокси |
| Совместимость TCP | ✅ gstatic 204 через hostname CONNECT |

---

## 6. Проверка TLS

- DoH: `tls.Client` + `ServerName=cloudflare-dns.com|dns.google`, `MinVersion=TLS1.2`, system roots.
- Интеграция: handshake + 200 OK на DoH POST через mock.
- HTTPS-прокси (`https://` scheme) — не менялся.

---

## 7. Проверка SERVFAIL (unit)

| Ассерт | Статус |
|---|---|
| ID запроса = ID ответа | ✅ |
| QR=1 | ✅ |
| RCODE=2 | ✅ |
| QDCOUNT=1, AN/NS/AR=0 | ✅ |
| Вопрос байт-в-байт | ✅ |
| RD сохранён | ✅ |
| Короткий запрос → nil (без фантома) | ✅ |

---

## 8. Сборка APK

| | |
|---|---|
| versionName | **2.4** |
| versionCode | **15** |
| packageName | **com.weathercurrency.app** |
| Путь | `/opt/weather-currency/output/WeatherCurrency-v2.4.apk` |
| Размер | **36 228 316** байт |
| **SHA-256** | **`8499959c902a5133f2be3d1bff962164ebeb505475fe6e7d80c858049cbb1f2a`** |
| badging | `versionCode='15' versionName='2.4'` |
| AAR | `app/libs/tun2socks.aar` (пересобран; строки hostname/SERVFAIL в `libgojni.so` ✅) |

### Подпись APK

```
Signer #1 certificate DN: CN=WeatherCurrency Debug, OU=WeatherCurrency, O=WeatherCurrency, L=NA, C=RU
Signer #1 certificate SHA-256 digest: 90eaea49f52b8fd11c0cc70df69c88d2f1ba220614fcfbd6e35fc7c4fb42fe54
```

**Совпадает с v2.3** (и с исторической цепочкой `90eaea49…`) — обновление поверх установится.

Строки движка в APK (`lib/arm64-v8a/libgojni.so`): `hostname CONNECT`, `[DNS] SERVFAIL`, `DoH ok for`, `WeatherCurrency/2.4`, `cloudflare-dns.com`, `dns.google` — **все OK**.

---

## 9. Ограничения

1. **GitHub Release не публиковался**; OTA-канал не изменён; `/releases/latest` остаётся v2.2 (ожидаемо).
2. **Реальный пользовательский прокси не тестировался** (секреты); DoH/TCP — mock CONNECT + прямая сеть.
3. **Android E2E нет** (нет READ_LOGS/root/adb) — Chrome/YouTube на устройстве не проверены.
4. IPv6-CONNECT и Private DNS/Secure DNS **не закрыты** этой задачей (вне scope; см. TASK19 F4/F6).
5. QUIC/UDP-поведение **не менялось** (F2 остаётся).
6. `PROJECT_STATE.md`/`NEXT_SESSION.md`/`DECISIONS.md` отсутствуют.

---

## 10. Инструкция по проверке Chrome/YouTube (на устройстве)

1. Установить **`output/WeatherCurrency-v2.4.apk`** поверх v2.3 (подпись совпадает).
2. Настроить тот же HTTP/HTTPS-прокси; **«Тест прокси (мимо VPN)»** → должен быть OK (CONNECT по имени).
3. Включить **Proxy VPN**.
4. **Chrome:** открыть несколько обычных HTTPS-сайтов (например, `example.com`, `wikipedia.org`).
5. **YouTube:** главная страница + одно видео.
6. **Telegram:** проверить, что по-прежнему работает.
7. Отключить VPN.
8. При проблемах — логи (уровень **info**):
   ```bash
   adb logcat -v time | grep -E "ProxyVpn|\[DNS\]|\[UDP\]|\[TCP\]|DoH|SERVFAIL"
   ```
   Ожидаемые события:
   - `[DNS] … -> DoH via proxy (hostname CONNECT)`
   - `[DNS] DoH ok for …` — DNS работает;
   - `[DNS] DoH … failed` + `[DNS] SERVFAIL -> …` — оба DoH недоступны через прокси;
   - `[TCP] …` — обычные сайты через CONNECT;
   - `[UDP] … dropped` — QUIC (норм для этого прокси).

**Не публиковать** этот APK как стабильный релиз без отдельного разрешения.

---

## ИТОГ

| | |
|---|---|
| DoH CONNECT | **hostname** `cloudflare-dns.com` / `dns.google` ✅ |
| SERVFAIL при отказе DoH | ✅ (txid + вопрос + rcode=2) |
| Локальный DNS-резолв DoH | ✅ отсутствует |
| TLS | ✅ проверка сертификатов сохранена |
| Диагностика | ✅ уровень `info`; SERVFAIL/ошибки — Warn |
| Тесты Go | ✅ `tunnel` PASS (+ прежние ok) |
| Тесты Kotlin | ✅ **157/157** |
| Интеграция mock | ✅ hostname CONNECT + DoH ×2 + TCP 204 |
| APK | **2.4/15**, SHA-256 `8499959c…`, подпись `90eaea49…` = v2.3 |
| Публикация | **не выполнена** — жду разрешения |

**Останавливаюсь. Не публикую GitHub Release и не изменяю OTA без отдельного разрешения пользователя.**
