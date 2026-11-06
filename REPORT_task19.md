# REPORT — TASK №19 — FULL NETWORK AUDIT WeatherCurrency v2.3

Исполнитель: MiMo Code
Дата: 2026-11-05
Проект: `/opt/weather-currency` (WeatherCurrency **2.3**, versionCode **14**)
GitHub: `metanikajkj-netizen/Weather-and-exchange-rates`
Режим: **STRICT READ-ONLY** (исходники/APK/релизы/настройки не изменялись; временные тесты — вне дерева проекта в `/tmp/vpn-audit/`)

---

## 0. Подготовка и документация

| Файл | Статус |
|---|---|
| `PROJECT_STATE.md` / `NEXT_SESSION.md` / `DECISIONS.md` | **отсутствуют** (не созданы) |
| `REPORT_task13.md`–`REPORT_task17.md` | **изучены** |
| `REPORT_task18.md` | отсутствует (TASK18 остановлен на подготовке Android) |

**Симптомы (от пользователя):** при включённом Proxy VPN — Telegram работает; Chrome не загружает сайты; YouTube не загружает данные. Поведение одинаково на v2.2 и v2.3.

---

## 1. Краткий технический вывод

**Единая архитектура объясняет расклад «Telegram = OK, Chrome/YouTube = FAIL» без единой «тотальной» поломки CONNECT:**

1. **TCP через HTTP CONNECT работает** (код + локальные тесты + симптом Telegram). Тотального отказа CONNECT нет.
2. **UDP (QUIC/HTTP3) жёстко отбрасывается** без ICMP — Chrome/YouTube по умолчанию пробуют HTTP/3 на `:443` и могут долго ждать/ретраить UDP вместо быстрого TCP-fallback. Telegram (MTProto/TCP) этого не делает.
3. **DNS перехвачен только на UDP/53** и резолвится DoH через CONNECT к **сырым IP** `1.1.1.1:443` / `8.8.8.8:443`. Если прокси разрешает CONNECT по **именам** (как в «Тест прокси» → `www.gstatic.com:443`), но режет CONNECT по IP или закрывает DoH — **DNS падает целиком**: Chrome/YouTube не резолвятся, Telegram продолжает работать по кешу/известным IP DC.
4. При ошибке DoH движок **не шлёт клиенту SERVFAIL** — клиент висит до своего таймаута (ощущение «сайт не открывается»).
5. **Private DNS (DoT :853) и Secure DNS Chrome** (собственный DoH, часто поверх HTTP/3) **не перехватываются** кодом UDP/53; DoT идёт обычным TCP через CONNECT, DoH Chrome может упасть в тот же UDP/443-drop.
6. **IPv6 `::/0` заявлен**, DNS-серверы только IPv4; при AAAA и попытке IPv6-CONNECT прокси может отказать — риск частичных отказов; утечки прямого IPv6 в коде нет.

**Главный кандидат причины отказа Chrome/YouTube при живом Telegram:** сочетание **(A)** DoH-CONNECT по IP может блокироваться прокси → нет DNS, и/или **(B)** QUIC-drop без быстрого TCP-fallback → зависания HTTP/3.

---

## 2. Архитектура VPN (по исходникам)

### 2.1 Сверка исходников ↔ AAR ↔ APK ↔ GitHub

| Проверка | Результат |
|---|---|
| `app/build.gradle.kts` | `versionName=2.3`, `versionCode=14`, `applicationId=com.weathercurrency.app` |
| AAR `app/libs/tun2socks.aar` | 8 044 650 B; `libgojni.so` 23 638 592 B, SHA256-префикс `4240e27eb01ae6b0` |
| APK `output/WeatherCurrency-v2.3.apk` | 36 211 932 B, SHA256 `2af3a307a4f271125b8ef7769c3c149159f3d166418f32f190c17bc2eb6dea6a` |
| `libgojni.so` внутри APK | **IDENTICAL** с AAR (тот же SHA) |
| `aapt2 dump badging` APK | `versionCode='14' versionName='2.3'` |
| GitHub Release `v2.3` | prerelease=true; assets: APK 36 211 932 B + SHA256SUMS — **размер совпадает** с локальным |
| `/sdcard/Download/WeatherCurrency-v2.3.apk` (устройство) | SHA256 **совпадает** с локальным |
| Строки движка в `libgojni.so` | `[DNS] %s -> DoH via proxy`, `[DNS] write response`, `[UDP] … dropped (proxy has no UDP support…)` |

**Локальные патчи tun2socks** (`git status`): `proxy/http/http.go`, `tunnel/udp.go`, новый `tunnel/dns.go`, `go.mod`/`go.sum` (gomobile). В AAR/APK они присутствуют.

### 2.2 Android VpnService (`ProxyVpnService.kt`)

| Параметр | Значение | Строка |
|---|---|---|
| Session | `WeatherCurrency Proxy` | `:84` |
| MTU | **1500** | `:85` |
| IPv4 | `172.19.0.1/32` | `:86` |
| Route v4 | `0.0.0.0/0` | `:87` |
| IPv6 | `fdfe:dcba:9876::1/126` | `:88` |
| Route v6 | `::/0` | `:89` |
| DNS | `8.8.8.8`, `1.1.1.1` (только IPv4) | `:92–93` |
| Anti-loop | `addDisallowedApplication(packageName)` | `:94` |
| TUN FD → Go | `key.setDevice("fd://${pfd.fd}")` | `:106` |
| Proxy URL | `http(s)://[user:pass@]host:port` (`ProxyConfig.toProxyUrl`) | `ProxyConfig.kt:21–28` |
| Лог-уровень движка | **`warn`** | `:108` |
| MTU движка | `setMTU(0)` → default **1500** (`fdbased.Open`) | `:110` |
| `establish()==null` | ошибка «другой VPN» | `:97–101` |

`AndroidManifest.xml`: `ProxyVpnService` + `BIND_VPN_SERVICE`, `foregroundServiceType=specialUse`, `SUPPORTS_ALWAYS_ON=false`.

**Ключевой факт:** свой UID **не входит в TUN** → сокеты движка (включая DoH-CONNECT) идут **мимо VPN** напрямую к прокси. Это правильный anti-loop, но означает: **успех «Тест прокси» не доказывает путь Chrome через TUN** (подписано в UI с v2.3).

### 2.3 Пакет данных Go-движка

```
Android-приложение (UID A)
  ├─ сокеты прокси/DoH  → addDisallowedApplication → НЕ в TUN → прокси
  └─ Chrome/YouTube/TG  → TUN → gvisor stack → handleTCPConn / handleUDPConn
                                                    │
                              TCP  → Proxy().DialContext → shakeHand CONNECT → TLS
                              UDP/53 → handleDNSUDP → DoH POST через тот же CONNECT
                              UDP/other → DialUDP=ErrUnsupported → drop
```

---

## 3. Результаты аудита DNS (приоритет №1)

### 3.1 Полный путь (код)

| Шаг | Файл:строки | Статус |
|---|---|---|
| Перехват UDP/53 до DialUDP | `tunnel/udp.go:31–34` → `isDNSDestination` (`dns.go:39–41`) | ✅ |
| Метаданные: DstPort = `id.LocalPort` (порт назначения) | `udp.go:25–26` | ✅ |
| DoH: CONNECT к `1.1.1.1:443` / `8.8.8.8:443` | `dns.go:29–36, 102–111` | ✅ код |
| TLS SNI `cloudflare-dns.com` / `dns.google`, без `InsecureSkipVerify` | `dns.go:114–118` | ✅ |
| POST `/dns-query`, `Content-Type: application/dns-message` | `dns.go:126–146` | ✅ |
| HTTP 200 + body ≥ 12 | `dns.go:159–168` | ✅ |
| Ответ → TUN: `uc.WriteTo(resp, from)` | `dns.go:72–74` | ✅ код (см. 3.3) |
| Ошибка DoH → `continue` (**без ответа клиенту**) | `dns.go:67–70` | ⚠️ см. F1 |
| Таймауты: flow 12 c, DoH 6 c | `dns.go:25,124` | ✅ |
| Параллельные запросы | по flow — отдельная goroutine (`tunnel.go` process); **внутри flow — строго последовательно** | ✅ |

### 3.2 A/AAAA, transaction ID, checksums

- Wire-формат DNS пересылается в DoH **как есть** (query bytes) — A/AAAA/NXDOMAIN/timeout наследуются от upstream (Cloudflare/Google).
- **Transaction ID:** DoH-сервер возвращает ответ с тем же ID, что и в query (проверено тестом: query `0x1234` → ответ `0x1234`, `rcode=0`).
- **UDP/IP checksums:** считаются gvisor при `endpoint.Write` (`gvisor …/udp/endpoint.go` — `CalculateChecksum` + pseudo-header). Код приложения checksum не трогает.
- Валидация ID ответа с query в `dns.go` **не выполняется** (принимается body как есть) — риск низкий при честном DoH-upstream.

### 3.3 `WriteTo(from)` vs `Write(nil)` — проверка гипотезы TASK17

**Код gvisor (v0.0.0-20260701204157):**

- `udp.ForwarderRequest.CreateEndpoint` **Connect** endpoint к источнику пакета (клиенту) — `forwarder.go` (`ep.net.Connect(... SourceAddress ...)`).
- `gonet.UDPConn.WriteTo(b, addr)`: `addr != nil` → `writeOptions.To = addr`; `addr == nil` → отправка в connected peer.
- `gonet.UDPConn.Write(b)` ≡ `WriteTo(b, nil)`.

**Вывод:** endpoint DNS-flow **уже connected к клиенту**. И `WriteTo(resp, from)` (from = клиент из `ReadFrom`), и `Write(resp)` отправят ответ клиенту. **Гипотеза «ответ теряется именно из-за WriteTo vs Write» кодом НЕ подтверждается.** Остаётся UNVERIFIED на устройстве (нужен logcat `[DNS] write response` / packet capture).

### 3.4 Доступность DoH через прокси (тесты, см. §8)

| Проверка | Среда | Результат |
|---|---|---|
| DoH POST `1.1.1.1` + SNI cloudflare (напрямую, без прокси) | хост сборки | ✅ 200, rcode=0 |
| DoH POST `8.8.8.8` + SNI dns.google (напрямую) | хост | ✅ 200, rcode=0 |
| DoH через **mock CONNECT** `1.1.1.1:443` | mock | ✅ 200, body=124, an=6 |
| DoH через **mock CONNECT** `8.8.8.8:443` | mock | ✅ 200, body=93, an=4 |
| CONNECT `8.8.8.8:53` / `1.1.1.1:853` | mock (разрешены) | ✅ 200 Connection Established |
| **Реальный пользовательский прокси** | — | **не тестировался** (секреты; §12) |

### 3.5 Private DNS / Secure DNS / утечки

| Вопрос | Ответ по коду/тестам |
|---|---|
| Private DNS (DoT TCP/853) | **Не перехватывается.** Идёт `handleTCPConn` → CONNECT `host:853`. Могут блокироваться прокси (не-443). Значение `settings secure private_dns_*` **не прочитано** (нет прав — TASK17/18). |
| Secure DNS Chrome | Свой DoH (обычно HTTPS/443 через CONNECT — ок; **HTTP/3 → UDP/443 → drop** — плохо). Вне нашего UDP/53. |
| Прямая DNS-утечка из кода | Нет: UDP/53 перехвачен; DoH идёт только через `Proxy().DialContext`; свой UID мимо TUN **не** ходит в системный резолвер TUN. |
| IPv6 DNS-сервер | В v2.3 **не назначается** (`8.8.8.8`+`1.1.1.1`). |

---

## 4. Результаты аудита TCP / HTTP CONNECT

### 4.1 Код

| Пункт | Файл:строки | Статус |
|---|---|---|
| TCP → метаданные → `DialContext` | `tunnel/tcp.go:17–36` | ✅ |
| CONNECT + `Proxy-Connection: Keep-Alive` | `proxy/http/http.go:87–106` | ✅ |
| Basic `Proxy-Authorization` при user+pass | `http.go:100–102,125–128` | ✅ |
| 200 → OK; 407/405/другое → ошибка | `http.go:113–122` | ✅ |
| TLS до прокси (`https://` scheme) + system roots | `http.go:58–73` (`NewTLS`) | ✅ |
| `DialUDP` = `errors.ErrUnsupported` | `http.go:83–85` | ✅ |
| Таймаут CONNECT 5 c; half-close 60 c | `tunnel/tunnel.go:17–19`; `tcp.go:64–72` | ✅ |
| Relay bidirectional + half-close | `tcp.go:47–73` | ✅ |
| Keep-alive на сокете к прокси | `http.go:56` | ✅ |

**Бинарные данные / HTTP2 / большие ответы:** relay — обычный `io.CopyBuffer` без текстовых ограничений; HTTP/2 — end-to-end внутри туннеля после CONNECT (прокси не видит ALPN). Локальный тест: **1 340 194 байт** через CONNECT+TLS — OK.

### 4.2 Может ли CONNECT поддерживать Telegram, но ломать обычные HTTPS?

**Нет внутреннего разделения по хостам** — все TCP идут одним `shakeHand`. Разница между приложениями **не в реализации CONNECT**, а в **поведении поверх** (DNS/QUIC/IPv6/сравнение §7).

---

## 5. Результаты аудита UDP / QUIC

| Пункт | Статус | Доказательство |
|---|---|---|
| UDP/443 (QUIC) | **fail-fast drop** | `udp.go:36–43`: `DialUDP` → `ErrUnsupported` → `return` + `defer uc.Close()` |
| ICMP unreachable | **не отправляется** | в drop-ветке нет генерации ICMP (только Echo в `core/icmp.go` для ping) |
| Зависание QUIC на стороне движка | нет (поток закрывается) | `udp.go:18,43` |
| Зависание на стороне клиента | **возможна** — Chrome ждёт handshake/retry QUIC | UNVERIFIED на устройстве; поведение клиента не детерминировано кодом |
| TCP fallback Chrome/YouTube | **не гарантирован** | HTTP/3 advertisement + happy-eyeballs; может ретраить UDP до таймаута |
| Прочий UDP (WebRTC, NTP, …) | drop | тот же ветка |
| DNS UDP/53 | **не drop** — перехват до DialUDP | `udp.go:31–34` |

**Не считаем TCP-fallback гарантированным** (требование TASK). Telegram QUIC не использует → симптом согласуется.

---

## 6. Результаты аудита IPv6 / TUN

| Пункт | Статус |
|---|---|
| Маршруты | `0.0.0.0/0` + `::/0` — весь трафик в TUN, **прямой IPv6-обход в коде нет** |
| DNS IPv6 | серверы только IPv4 (v2.3) |
| MTU | builder **1500** = fdbased default при `setMTU(0)` — **совпадают** |
| MSS/фрагментация | gvisor TCP; большие HTTPS-ответы в тесте OK; PMTU-ошибок в коде не обрабатывается отдельно |
| Передача FD | `fd://<n>` → `url.Parse` Host=`<n>` → `fdbased.Open` `Atoi` — **корректно** (`parse.go:66–74`, `fd_unix.go:25–36`) |
| Checksum IP/UDP/TCP | gvisor stack |
| IPv6 CONNECT | формат `DestinationAddress()` → `[2606:…]:443` — **корректный** для HTTP CONNECT |
| IPv6 blackhole | риск: `::/0` заявлен; если прокси не умеет IPv6-CONNECT — TCP/IPv6 падает; UDP/IPv6 drop. ICMP unreachable нет. **Качество IPv6-CONNECT прокси не проверялось** (на хосте сборки IPv6 `Network is unreachable`) |
| Контрольные суммы TUN | gvisor при записи пакетов в fd |

---

## 7. Сравнение Telegram vs Chrome vs YouTube

| | **Telegram** | **Chrome** | **YouTube** |
|---|---|---|---|
| Транспорт | **TCP** MTProto (443/5222) | TCP TLS + **HTTP/3 QUIC (UDP/443)** по умолчанию | TCP + **активный QUIC**, видео/CDN |
| DNS | Кеш DC, **известные IP**, соединения не на каждый хост | **Резолв каждого домена**; Secure DNS (DoH) | Резолв googlevideo/CDN; DoH возможен |
| IPv6 | Обычно терпим к v4-only | Happy Eyeballs; AAAA → IPv6-CONNECT | аналогично |
| Наш UDP/443 | **не использует** | **попадает в drop** | **попадает в drop** |
| Наш DNS UDP/53→DoH | почти не нужен для базового канала | **критичен** | **критичен** |
| Итог при данных ограничениях | **работает** | **не грузит** | **не грузит данные** |

**Особенности реализации VPN, объясняющие симптомы:**

1. `DialUDP → ErrUnsupported` + мгновенный close (**без ICMP**) → QUIC-клиенты не получают быстрого «порт недоступен».
2. DoH только через CONNECT к **IP:443** — может не пройти политику прокси, в отличие от CONNECT по имени в «Тесте прокси».
3. Ошибка DoH **без SERVFAIL** → долгие зависания Chrome вместо быстрого NXDOMAIN/ServerError.
4. `setLogLevel("warn")` **прячет** успешные `[DNS] Infof` — на устройстве в логах видны только ошибки (диагностическая слепота).
5. Private DNS / Secure DNS — **вне** перехвата UDP/53.

---

## 8. Результаты выполненных тестов

**Среда:** Ubuntu proot (телефон), сеть с доступом к 1.1.1.1/8.8.8.8/www.*; mock CONNECT-прокси в `/tmp/vpn-audit/` (порт 18080); Go-тесты в `/tmp/vpn-audit/gotest` (replace → `/opt/tun2socks`).

### 8.1 Существующие unit-тесты (без изменения исходников)

| Набор | Результат |
|---|---|
| Kotlin `./build.sh test` | **BUILD SUCCESSFUL**; **157/157** (14 классов), 0 failures |
| Go `go test ./...` (tun2socks) | **ok** (allocator, pool, socks4, socks5); пакеты tunnel/proxy/http — no test files |

### 8.2 Временные Go-тесты (mock CONNECT, `/tmp/vpn-audit/gotest`)

| Тест | Результат |
|---|---|
| `TestConnectAndTLS` — CONNECT + TLS + GET gstatic | **PASS** (`HTTP/1.1 204`, len=203) |
| `TestConnectBlockedPort` — запрет CONNECT :53 | **PASS** (`403 Forbidden`) |
| `TestDialUDPNunsupported` | **PASS** (`unsupported operation`) |
| `TestParallelConnects` — 8× CONNECT+TLS+GET | **PASS** |
| `TestDoHStyleConnect` — CONNECT `1.1.1.1:443` + TLS SNI + POST DoH | **PASS** (body=61, txid=0x1234) |

### 8.3 Python-сценарии через живой mock-прокси 18080

| Сценарий | Результат |
|---|---|
| DoH via CONNECT `1.1.1.1` (SNI cloudflare-dns.com) | **OK** 200, rcode=0, an=6, 0.27 s |
| DoH via CONNECT `8.8.8.8` (SNI dns.google) | **OK** 200, rcode=0, an=4, 0.38 s |
| CONNECT `8.8.8.8:53` | **200** (в mock разрешён) |
| CONNECT `1.1.1.1:853` DoT | **200** (в mock разрешён) |
| HTTPS large `www.cloudflare.com` | **OK** 1 340 194 B, 6.51 s |
| TLS handshake gstatic | **OK** TLSv1.3 |
| Parallel 6× CONNECT+TLS+GET | **OK** 6/6 |
| CONNECT IPv6 `2606:4700:4700::1111:443` | **FAIL** — на хосте IPv6 `Network is unreachable` (ограничение среды, не прокси) |

**Классификация тестов:**

- **Mock HTTP CONNECT:** все §8.2–8.3 (кроме прямого DoH).
- **Без прокси (прямая сеть):** DoH 1.1.1.1/8.8.8.8 (§3.4).
- **Реальный прокси пользователя:** **не выполнялся** (§10–12).
- **Android E2E / logcat VPN:** **не выполнялся** (нет READ_LOGS/root/adb — TASK17/18).

---

## 9. Подтверждённые ошибки и находки

### Классификация

| ID | Статус | Влияние на Chrome/YouTube | Почему Telegram может работать |
|---|---|---|---|
| **F1** | **CONFIRMED** (код) | DoH fail → **нет DNS-ответа клиенту** → Chrome висит до своего таймаута на каждом запросе | Telegram не резолвит каждый домен |
| **F2** | **CONFIRMED** (код+тест) | UDP/443 **drop без ICMP** → QUIC-ретраи/ожидание вместо быстрого TCP | MTProto = TCP |
| **F3** | **HIGH PROBABILITY** | DoH-CONNECT по **сырому IP** может быть запрещён прокси (имена — можно) → **полный отказ DNS** | Известные IP DC, кеш |
| **F4** | **HIGH PROBABILITY** | Private DNS/Secure DNS **мимо** UDP/53; DoT :853 может резаться прокси; Secure DNS over HTTP/3 → drop | Не использует эти механизмы |
| **F5** | **CONFIRMED** (код) | `setLogLevel("warn")` **скрывает** успешные `[DNS] Infof` — нельзя подтвердить путь DNS по логам устройства | — |
| **F6** | **CONFIRMED** (код) | IPv6 `::/0` + возможный отказ IPv6-CONNECT / отсутствие ICMP → частичные отказы при AAAA | TG обычно v4-устойчив |
| **F7** | **UNVERIFIED** (устройство) | Гипотеза TASK17 «WriteTo теряет ответ» — **кодом не подтверждена** (endpoint connected к клиенту) | — |
| **F8** | **CONFIRMED** (код) | Успех «Тест прокси» **не** означает работу VPN-path (`addDisallowedApplication`) | — |

### Файлы и строки (минимальные исправления)

| Находка | Файл:строки | Суть | Минимальное исправление |
|---|---|---|---|
| **F1** | `tunnel/dns.go:67–70` | при ошибке DoH — `continue` без ответа | сформировать DNS-ответ с rcode=SERVFAIL (2) и `WriteTo` клиенту; лог Warn |
| **F2** | `tunnel/udp.go:36–43` | drop QUIC без ICMP / без подсказки | (опц.) ICMP Port Unreachable для закрытого flow; не обещать fallback |
| **F3** | `tunnel/dns.go:102–111` + `proxy/http/http.go:88` | CONNECT `1.1.1.1:443` / `8.8.8.8:443` | CONNECT по **имени**: `cloudflare-dns.com:443` / `dns.google:443` (SNI уже совпадает) |
| **F5** | `ProxyVpnService.kt:108` + `dns.go:47` | уровень `warn` vs `Infof` | для диагностики временно `info` **или** ключевые DNS-события `Warnf` |
| **F6** | `ProxyVpnService.kt:88–89` | `::/0` без IPv6-CONNECT гарантии | документировать; опц. отключать `::/0` если прокси v4-only (**не** делать скрытый bypass) |
| **F8** | `ProxyVpnService.kt:94` | anti-loop корректен | UI уже помечает тест «мимо VPN» (v2.3) |

---

## 10. Приоритетный план исправления

| Приор. | Действие | Цель |
|---|---|---|
| **P0** | **F3:** DoH CONNECT по имени хоста (`cloudflare-dns.com` / `dns.google`), а не по IP | Пройти типичную политику «только hostname:443» |
| **P0** | **F1:** SERVFAIL клиенту при сбое DoH | Убрать «вечное» ожидание Chrome |
| **P1** | **F4:** инструкция/диагностика Private DNS + Secure DNS (запрос значений к пользователю или adb) | Убрать скрытые обходы UDP/53 |
| **P1** | **F5:** логировать DNS-путь на уровне, видимом в logcat (`info` или Warnf-маркеры) | Сделать E2E доказуемым |
| **P2** | **F2:** оценить ICMP unreachable для UDP-drop | Ускорить TCP-fallback HTTP/3 |
| **P2** | **F6:** решение по `::/0` после проверки IPv6-CONNECT прокси | Убрать blackhole-риск |
| **P3** | E2E на устройстве с READ_LOGS (из TASK17/18) | Закрыть F7/UNVERIFIED |

**Не требуется:** полная замена tun2socks; скрытый direct-bypass для Chrome/YouTube/DNS; sing-box (без нового требования).

---

## 11. Одно рекомендуемое минимальное исправление для следующей задачи

> **В `tunnel/dns.go` (`dohOnce`/`dohUpstreams`) перейти на HTTP CONNECT к DoH-**именам** (`cloudflare-dns.com:443`, `dns.google:443`) вместо сырых IP `1.1.1.1`/`8.8.8.8`, и в `handleDNSUDP` при полном отказе DoH отправлять клиенту DNS-ответ с **rcode=SERVFAIL**, а не молча `continue`.**

**Почему это одно изменение (пара точек в одном модуле DNS):**

1. «Тест прокси» пользователя уже доказывает работоспособность CONNECT **по имени** (`www.gstatic.com:443`).
2. CONNECT по IP к DoH — **не доказан** на реальном прокси и типично блокируется.
3. SERVFAIL даёт Chrome немедленный отказ DNS вместо зависания — в сочетании с работающим TCP/443 Chrome сможет показать ошибку/повторить быстро, а при успешном DoH — загрузить сайты.
4. QUIC/F2 остаётся отдельным P2; Telegram не ломается (TCP path не трогаем).

**Валидация после правки (следующая задача, с разрешением):** unit-тест DoH-URL; локальный mock: CONNECT hostname:443 + POST; на устройстве: logcat `[DNS]` + открытие Chrome/YouTube.

---

## 12. Ограничения аудита

1. **STRICT READ-ONLY** — исходники, APK, GitHub-релизы, настройки Android/прокси не изменялись.
2. **Реальный прокси не тестировался** (учётные данные не использовались/не выводились); все прокси-тесты — **mock** или прямая сеть.
3. **Android E2E нет:** нет READ_LOGS/root/adb (TASK17/18) — поведение Chrome/YouTube/Telegram, Private DNS, фактический logcat DoH/QUIC — **не сняты**.
4. **IPv6-CONNECT** не проверен вживую (на хосте нет IPv6-маршрута).
5. Пароль/логин прокси, `Proxy-Authorization`, содержимое трафика — **не извлекались**.
6. `PROJECT_STATE.md`/`NEXT_SESSION.md`/`DECISIONS.md` отсутствуют; `REPORT_task18.md` отсутствует (задача остановлена).
7. Unit-тесты не покрывают `tunnel/dns.go` (в репо tun2socks нет `_test.go` для tunnel/proxy/http).

---

## ИТОГ (сводка для §11 отчёта)

| Раздел | Результат |
|---|---|
| Архитектура VPN | Описана; AAR=APK=GitHub v2.3/14 **сверено** |
| DNS | Путь полон; DoH-тесты на mock ✅; **F1** (нет SERVFAIL), **F3** (IP-CONNECT), **F5** (лог warn) |
| TCP/CONNECT | Код и тесты ✅; 1.3 MB / parallel / TLS ✅ |
| UDP/QUIC | **F2** fail-fast drop без ICMP; fallback не гарантирован |
| IPv6/TUN | FD/MTU/маршруты ✅; **F6** blackhole-риск IPv6 |
| Telegram vs Chrome vs YouTube | TCP+IP-кеш vs DNS+QUIC — **объясняет симптомы** |
| Тесты | Kotlin **157/157**; Go tun2socks **ok**; audit Go **5/5**; DoH/HTTPS/parallel mock ✅ |
| Подтверждённые ошибки | F1, F2, F5, F6, F8 — CONFIRMED; F3, F4 — HIGH PROBABILITY; F7 — UNVERIFIED |
| **Минимальное исправление (след. задача)** | **DoH CONNECT по hostname + SERVFAIL при сбое DoH** (`tunnel/dns.go`) |

**После завершения — остановиться. Ничего не исправлять без отдельного разрешения пользователя.**
