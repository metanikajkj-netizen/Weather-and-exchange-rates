# REPORT — TASK №23 — Исправление IPv6 / принудительный IPv4 для WeatherCurrency

Исполнитель: MiMo Code
Дата: 2026-11-06
Проект: `/opt/weather-currency`
Версия: **2.4 / 15 → 2.5 / 16**
packageName: `com.weathercurrency.app` (не изменён)

---

## 0. Документация

| Файл | Статус |
|---|---|
| `REPORT_task22.md` | изучен (LIVE: IPv4 OK, IPv6 TLS EOF, 2ip без AAAA работает) |
| `REPORT_task20.md`, `REPORT_task19.md` | изучены |
| `PROJECT_STATE.md` / `NEXT_SESSION.md` / `DECISIONS.md` | **отсутствуют** (не созданы) |

---

## 1. Причина неисправности (из TASK22, подтверждена)

- DNS через VPN **работает** (A и AAAA, rcode=0).
- **IPv4 HTTPS через прокси работает** (`curl -4` → 200/204).
- **IPv6 TCP** принимается локально gvisor (~1 ms), затем HTTP CONNECT к IPv6-адресу через прокси **обрывается** → TLS EOF.
- Клиенты с pref IPv6 (Chrome, curl default, YouTube) падают; **2ip.io без AAAA** работал.
- Двойные сайты (google/youtube/example/gstatic) имеют **AAAA** → выбирали IPv6 → отказ.

---

## 2. Изменённые файлы

| Файл | Изменение |
|---|---|
| **`/opt/tun2socks/tunnel/dns.go`** | AAAA→NODATA; `stripAAAA`/`filterAAAA` через **dnsmessage**; `buildDNSReply`; UA 2.5 |
| **`/opt/tun2socks/tunnel/tcp.go`** | Fast-close **IPv6 TCP** (IPv4-only политика) |
| **`/opt/tun2socks/tunnel/dns_test.go`** | Расширен: AAAA/NODATA/strip/CNAME/SERVFAIL/IPv6-detect |
| **`/opt/tun2socks/go.mod`** | `golang.org/x/net` → direct (dnsmessage) |
| **`app/build.gradle.kts`** | `versionName=2.5`, `versionCode=16` |
| `app/libs/tun2socks.aar` | пересобран (`gomobile bind`, exit 0) |
| `output/WeatherCurrency-v2.5.apk` | **новый** тестовый APK |

**Не менялось:** маршруты VpnService (в т.ч. **`::/0` остался** — `ProxyVpnService.kt:89`), IPv4 TCP/CONNECT/авторизация, Telegram-логика, UA proxy settings, keystore, OTA.

---

## 3. Реализация AAAA-фильтрации

Библиотека: **`golang.org/x/net/dns/dnsmessage`** (безопасный parse/pack, **не** вырезание байтов).

### 3.1 AAAA-вопрос → NODATA (без DoH)

В `handleDNSUDP` перед DoH:

- `queryType(query)` == **28** → `buildDNSReply(query, RCodeSuccess)`:
  - **txid**, Opcode, RD, **секция Question** — из запроса;
  - QR=1, RA=1, **RCODE=0 (NOERROR)**, AN/NS/AR=0;
  - **не SERVFAIL** (отсутствие IPv6 — здоровый ответ);
  - DoH не вызывается (нет цикла и лишней нагрузки).

### 3.2 Смешанные ответы → `filterAAAA`

После успешного `exchangeDoH`:

1. `stripAAAA(resp)` → `dnsmessage.Message.Unpack`;
2. удалить **все** `TypeAAAA` из Answers / Authorities / Additionals;
3. A, CNAME, NS, OPT/EDNS0 и др. **сохраняются**; `Pack()` пересчитывает **счётчики секций** и корректно пакует сжатие имён;
4. если AAAA не было — возвращаются **исходные байты** (без перепаковки);
5. при ошибке парсинга: для AAAA-вопроса — NODATA-fallback; иначе pass-through (не ломаем чужие ответы).

### 3.3 SERVFAIL (не нарушен)

`buildSERVFAIL` = `buildDNSReply(..., RCodeServerFailure)` — прежняя логика TASK20 при отказе обоих DoH.

### 3.4 Защита от IPv6-утечек

- **`addRoute("::", 0)` не удалён** — весь IPv6 остаётся в TUN.
- В `handleTCPConn`: если `DstIP` — **IPv6** (не 4-in-6) → **немедленный close** без Dial/CONNECT  
  → лог `[TCP] … closed (IPv4-only policy, no IPv6 CONNECT)`; клиент быстро ретраит IPv4.
- IPv4 TCP/CONNECT/авторизация — **без изменений**.

---

## 4. Результаты тестов

### 4.1 Go (`go test ./...`)

```
go build ./...     → ok
tunnel             → ok  (12/12 PASS)
buffer/allocator   → ok
internal/pool      → ok
transport/socks4   → ok
transport/socks5   → ok
```

| Тест | Статус |
|---|---|
| `TestQueryTypeAAAA` | PASS |
| `TestBuildSERVFAILPreservesIDAndQuestion` | PASS |
| `TestBuildSERVFAILShortQuery` | PASS |
| `TestBuildNoDataAAAAKeepsTxidQuestionRcode0` | PASS (rcode=0 ≠ SERVFAIL) |
| `TestStripAAAAremovesOnlyAAAA` | PASS (A оставлен, 2×AAAA удалены, txid) |
| `TestStripAAAAKeepsAAndCNAME` | PASS |
| `TestStripAAAANoChangeReturnsOriginal` | PASS (байты идентичны) |
| `TestFilterAAAAOnAAAAOnlyAnswerNODATAPath` | PASS (parse fail → NODATA) |
| `TestFilterAAAAStripsFromGoodResponse` | PASS |
| `TestConnectAddressHostname` / `IPFallback` | PASS |
| `TestIPv6DestinationDetection` | PASS (v4=false, v6=true, 4-in-6=false) |

### 4.2 Kotlin

```
./build.sh :app:testDebugUnitTest --rerun-tasks → BUILD SUCCESSFUL
tests=157 failures=0 (14 классов)
```

### 4.3 Реальный DoH + фильтр (offline, вне TUN)

Запрос к `cloudflare-dns.com` (прямой, не через телефонный VPN):

| Запрос | До фильтра | После `stripAAAA` |
|---|---|---|
| www.google.com **A** | 8× TypeA | (без AAAA — без изменений) |
| www.google.com **AAAA** | 8× TypeAAAA | **0 answers**, txid совпал, `changed=true` |

```
REAL_DOH_FILTER_OK
```

### 4.4 IPv4 / IPv6 (политика)

| Проверка | Результат |
|---|---|
| `::/0` в `ProxyVpnService.kt:89` | ✅ **сохранён** (нет утечки IPv6 мимо VPN) |
| IPv6 TCP в движке | ✅ fast-close до CONNECT |
| IPv4 TCP/CONNECT | ✅ не тронут |
| AAR строки | ✅ `[DNS] AAAA -> NODATA (IPv4-only)`, `[TCP] … IPv4-only policy`, `WeatherCurrency/2.5` |

### 4.5 Проверка на реальном прокси через **новую** DNS-логику VPN

**Не выполнена как E2E на устройстве:** на телефоне **всё ещё работает v2.4** (установка v2.5 и переподключение VPN в рамках задачи не выполнялись; перезапуск VPN запрещён без отдельного разрешения).

| Что можно утверждать | Статус |
|---|---|
| Юнит + реальный DoH-фильтр (offline) | ✅ доказано |
| `curl default` на устройстве **через новый движок** | ⏳ требует установки v2.5 + reconnect VPN |
| Ожидание после установки | default curl google/youtube → **200** (только IPv4 из getaddrinfo) |

**Не выдавался E2E PASS без запуска нового движка.**

---

## 5. APK

| | |
|---|---|
| Путь | `/opt/weather-currency/output/WeatherCurrency-v2.5.apk` |
| versionName / versionCode | **2.5 / 16** |
| packageName | `com.weathercurrency.app` |
| Размер | **36 424 924** байт |
| **SHA-256** | **`fc628a4158618001e524258a66599a581aa996ef924a7d8d758f65dd1a9d5625`** |
| Подпись | **`90eaea49f52b8fd11c0cc70df69c88d2f1ba220614fcfbd6e35fc7c4fb42fe54`** (CN=WeatherCurrency Debug) = v2.2–v2.4 |
| apksigner | SIGNATURE_VALID |
| GitHub Release | **не публиковался**; OTA **не изменялась** |

---

## 6. Оставшиеся риски

1. **E2E на устройстве не закрыт** — Chrome/YouTube нужно проверить после установки v2.5 и переподключения VPN.
2. **Кешированный AAAA** у Chrome до первого нового резолва — закрывается fast-close IPv6-TCP (ретрай IPv4).
3. **Прямые IPv6-цели** (hardcoded) — закрываются; если когда-либо появится IPv6-CONNECT у прокси, политику нужно будет пересмотреть.
4. **IPv6 UDP** — по-прежнему fail-fast drop (как v2.2+); QUIC — вне этой задачи.
5. parse-fail в `filterAAAA` → pass-through для не-AAAA вопросов (теоретический остаточный AAAA в экзотичном ответе).
6. Live `curl` default **до** установки v2.5 остаётся сломанным (движок v2.4).

---

## 7. Рекомендуемая проверка (после разрешения установить v2.5)

1. Установить `output/WeatherCurrency-v2.5.apk` поверх v2.4 (подпись совпадает).
2. Подключить Proxy VPN.
3. `curl -sS -o /dev/null -w '%{http_code}\n' https://www.google.com` → ожидание **200** (без `-4`).
4. Chrome: google.com, youtube.com; 2ip.io — по-прежнему открывается.
5. `adb logcat | grep '\[DNS\]'` → `AAAA -> NODATA (IPv4-only)`.

---

## ИТОГ

| | |
|---|---|
| Причина | IPv6 через TUN→CONNECT → TLS EOF; IPv4/DNS исправны |
| Фикс | **AAAA→NODATA + strip AAAA (dnsmessage) + fast-close IPv6 TCP**; `::/0` сохранён |
| Go | **12/12 PASS**; весь `go test ./...` ok |
| Kotlin | **157/157** |
| Реальный DoH+фильтр | **REAL_DOH_FILTER_OK** |
| APK | **2.5/16**, SHA-256 `fc628a41…`, подпись `90eaea49…` |
| Публикация | **не выполнялась** |

**Останавливаюсь. Не публикую и не устанавливаю APK без отдельного разрешения.**
