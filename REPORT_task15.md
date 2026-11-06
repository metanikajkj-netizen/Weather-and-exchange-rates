# REPORT — TASK №15 — Исправление DNS и маршрутизации Proxy VPN (v2.3)

Исполнитель: MiMo Code  
Дата: 2026-11-05  
Проект: `/opt/weather-currency`  
Тестовый APK: `/opt/weather-currency/output/WeatherCurrency-v2.3.apk`  
**GitHub Release: НЕ публиковался** (по заданию). APK на телефон **не устанавливался**.

---

## 0. Подготовка

| Файл | Статус |
|---|---|
| `PROJECT_STATE.md` / `NEXT_SESSION.md` / `DECISIONS.md` | **отсутствуют** |
| `REPORT_task13.md` | изучен (v2.2, tun2socks AAR, VpnService) |
| `REPORT_task14.md` | изучен (P1 DNS, P3 QUIC, P4 IPv6, P8 CONNECT) |
| Версия до задачи | **2.2 / versionCode 13** |
| Целевая версия | **2.3 / versionCode 14** |
| Keystore | **не изменён** |

**Задача:** Chrome/YouTube/Telegram через HTTP/HTTPS-прокси; **без** скрытого обхода прокси.

---

## 1. Причины ошибок (из Task14, подтверждённые кодом)

| ID | Причина | Код до правок |
|---|---|---|
| **P1** | DNS UDP/53 из TUN → `DialUDP` HTTP-прокси = `ErrUnsupported` → ответ приложению не возвращается → имена не резолвятся | `tunnel/udp.go` → `proxy/http/http.go:83-84` |
| **P3** | QUIC/UDP/443 молча отбрасывался | тот же путь `DialUDP` |
| **P4** | IPv6 DNS `2001:4860:…` + `::/0` — мёртвый UDP-путь | `ProxyVpnService.kt` |
| **Тест** | «Тестирование» шло **мимо TUN** (app UID в `addDisallowedApplication`) и не проверяло VPN | `ProxyVpnService.kt:89` |

TCP/80, TCP/443, TLS, CONNECT, Basic-auth — в коде корректны (не менялись, кроме окружения).

---

## 2. Изменения в DNS-движке (P1)

Новый файл **`/opt/tun2socks/tunnel/dns.go`** + правка **`tunnel/udp.go`**.

### Алгоритм
1. В `handleUDPConn` **до** `DialUDP`: если `DstPort == 53` → `handleDNSUDP`.
2. Читаем wire-format DNS query из TUN (UDP), **не** через `DialUDP`.
3. Резолв через **DoH поверх HTTP CONNECT**:
   - `Proxy().DialContext` → TCP к `1.1.1.1:443` / `8.8.8.8:443` (CONNECT у существующего прокси-клиента);
   - TLS-рукопожатие с `ServerName` `cloudflare-dns.com` / `dns.google`, **проверка сертификатов** (без `InsecureSkipVerify`);
   - `POST /dns-query`, `Content-Type: application/dns-message`;
   - fallback: cloudflare → google.
4. Ответ пишем в тот же UDP-flow (`WriteTo` исходному адресу клиента) — приложение видит ответ «от» запрошенного DNS-сервера.
5. Таймауты: чтение flow 12 c, DoH-запрос ~6 c; ошибки → лог + continue (ретраи клиента).

### Свойства
| Требование | Как закрыто |
|---|---|
| Перехват DNS из TUN | порт 53 в `handleUDPConn` |
| UDP/53 | основной путь |
| TCP/53 | уже работал через обычный TCP→CONNECT (`handleTCPConn`); не менялся |
| Резолв через прокси | DoH только через `Proxy().DialContext` (CONNECT) |
| A / AAAA / NXDOMAIN | wire-format запрос/ответ DoH — полные DNS-семантики |
| Таймауты | deadlines на UDP-flow и TLS/HTTP |
| Без DNS-loop | DoH идёт через прокси; свой UID мимо TUN; DoH не спрашивает TUN-DNS |
| Не мимо VPN | прямого `net.Resolver`/системного DNS в этом пути **нет** |

Сборка: `go build ./...` → ok; AAR пересобран `gomobile bind` → exit 0.  
В APK: строки движка `[DNS] %s -> DoH via proxy` присутствуют в `lib/arm64-v8a/libgojni.so`.

---

## 3. UDP / QUIC (P3)

В `tunnel/udp.go` для **не-DNS** UDP (QUIC/443 и т.п.):

- сохранён **fail-fast**: `DialUDP` → ошибка → **немедленное закрытие** сокета (без зависаний);
- уточнён лог: `[UDP] %s: dropped (proxy has no UDP support, e.g. QUIC): %v`;
- **не** инкапсулируем UDP в TCP;
- **не** отключаем VPN для Chrome/YouTube.

Ожидание: клиент (Chrome/YouTube) после быстрого отказа UDP переходит на **TCP/443 → CONNECT**.  
Гарантия работы всех UDP-приложений **не обещается** (протокольный предел HTTP CONNECT).

---

## 4. IPv6 (P4)

- **Маршруты `0.0.0.0/0` и `::/0` сохранены** — весь IPv4/IPv6-трафик приложений остаётся в TUN (**нет прямого обхода** по IPv6).
- TCP/IPv6 через CONNECT поддерживается движком (не «нерабочий маршрут» для TCP).
- UDP/IPv6 (кроме DNS) — та же fail-fast политика, что и IPv4 (см. §3).
- **DNS-серверы изменены** с `8.8.8.8` + `2001:4860:4860::8888` на **`8.8.8.8` + `1.1.1.1`** (только IPv4-цели в конфигурации): сами запросы перехватываются на порту 53 и уходят DoH через CONNECT; IPv6-DNS-сервер больше не предлагается системе (меньше мёртвых вариантов).
- Ограничение: если прокси принимает только IPv4-CONNECT, IPv6-TCP соединения завершатся ошибкой (fail-closed, не утечка). **Документировано.**

---

## 5. TCP и HTTP CONNECT

Без изменений протокольного кода (`proxy/http/http.go`):

- CONNECT + `Proxy-Authorization` Basic;
- HTTPS-прокси: TLS с проверкой сертификатов;
- таймауты OkHttp/VpnService не менялись;
- MTU TUN 1500 — без изменений.

DoH использует тот же `DialContext` → **тот же CONNECT**, что и обычный трафик.

---

## 6. Диагностика (раздел Proxy VPN)

| Элемент | Поведение |
|---|---|
| Кнопка | **«Тест прокси (мимо VPN)»** |
| Результат | префикс **«Тест прокси (мимо VPN): …»** |
| Строкa | «Системный VPN: активен/неактивен» (`TRANSPORT_VPN` + статус сервиса) |
| Подпись | тест проверяет **эндпоинт прокси**, не TUN других приложений |

Явно: **не путаем** успех теста прокси с работой Android VPN.

---

## 7. Сохранение функций

| Функция | Статус |
|---|---|
| Погода / валюты / криптовалюты | не изменялись |
| OTA / WorkManager / уведомления об обновлениях | не изменялись |
| Навигация, подпись APK, packageName | не изменялись |

---

## 8. Сборка и тесты

```bash
cd /opt/tun2socks && go build ./...          # ok
gomobile bind … ./engine                     # AAR exit 0
./build.sh :app:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL
./build.sh :app:testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL
```

| | |
|---|---|
| Тесты | **157 / 157 passed**, failures=0 |
| Новые | `ProxyVpnDiagTest` (4) |
| Регрессии | 153 прежних зелёные |

---

## 9. Параметры тестового APK

| | |
|---|---|
| BUILD | **SUCCESSFUL** |
| package | `com.weathercurrency.app` |
| versionName / versionCode | **2.3 / 14** |
| Подпись | **SIGNATURE_VALID** |
| Сертификат v2.2 == v2.3 | **CERTS_IDENTICAL** (`90eaea49…`) |
| Поверх v2.2 | **да** (14>13, same package, same cert) |
| Размер | **36 211 932** bytes |
| SHA-256 | **`2af3a307a4f271125b8ef7769c3c149159f3d166418f32f190c17bc2eb6dea6a`** |
| Путь | **`/opt/weather-currency/output/WeatherCurrency-v2.3.apk`** |

`unzip libgojni.so | strings` → `[DNS] %s -> DoH via proxy`, `[UDP] %s: dropped (proxy has no UDP support, e.g. QUIC)` — правки **внутри APK**.

**Не выполнялось:** `gh release create`, установка на телефон.

---

## 10. Изменённые файлы

| Файл | Изменение |
|---|---|
| `app/build.gradle.kts` | versionName **2.3**, versionCode **14** |
| `app/libs/tun2socks.aar` | пересобран (DNS DoH + UDP-лог) |
| **новый** `/opt/tun2socks/tunnel/dns.go` | DoH через CONNECT, обработка UDP/53 |
| `/opt/tun2socks/tunnel/udp.go` | перехват DNS до DialUDP; fail-fast QUIC-лог |
| `proxy/ProxyVpnService.kt` | DNS `8.8.8.8`/`1.1.1.1`; комментарии IPv6/анти-петля |
| `proxy/ProxyVpnViewModel.kt` | `systemVpnActive`, префикс теста «мимо VPN» |
| `ui/ProxyVpnScreen.kt` | кнопка/подписи теста, статус системного VPN, текст ограничений |
| `test/ProxyVpnDiagTest.kt` | **новый** — 4 теста |

---

## 11. Непроверенные ограничения

1. **E2E на Android не выполнялось** — Chrome/YouTube/Telegram, logcat, реальный прокси не проверялись (APK не устанавливался).
2. DoH зависит от того, разрешает ли прокси CONNECT к `1.1.1.1:443`/`8.8.8.8:443` (обычно да; если нет — DNS уйдёт ко второму upstream или упадёт — в логе `[DNS] DoH … failed`).
3. UDP/QUIC остаётся **неподдерживаемым** для HTTP CONNECT — не все приложения гарантируют быстрый fallback на TCP.
4. Приватный DNS (DoT/853) и Secure DNS Chrome — вне нашего TUN-перехвата (идут TCP/853 или свой DoH; TCP — через CONNECT).
5. IPv6-CONNECT зависит от прокси; при отказе — fail-closed.

---

## 12. Инструкция для реального испытания на Android

1. Установить `/opt/weather-currency/output/WeatherCurrency-v2.3.apk` поверх v2.2 (подпись совпадает).
2. Настроить прокси; нажать **«Тест прокси (мимо VPN)»** — ожидание: успех при рабочем прокси (это **не** тест TUN).
3. **Подключить VPN**; в UI «Системный VPN: активен».
4. `adb logcat` искать: `[DNS]`, `[UDP]`, `[TCP]` (тег движка/`ProxyVpnService`).
5. Проверить DNS «через VPN»: открыть сайт по домену в Chrome, `nslookup google.com` в терминале **другого приложения**.
6. Chrome: главные сайты; при необходимости сравнить с `chrome://flags` QUIC off (A/B для §3).
7. YouTube: воспроизведение видео (`*.googlevideo.com`).
8. Telegram: обычное использование.
9. Лог прокси: должны появиться **CONNECT к DoH (1.1.1.1/8.8.8.8:443)** и CONNECT к сайтам — доказательство, что DNS и TCP идут **через прокси**, а не мимо.
10. Отключить VPN → сравнить; IPv6: при проблемах проверить, нет ли прямых IPv6-соединений мимо VPN (`adb shell ss -tun`).

Критерий успеха: домены резолвятся при активном VPN, Chrome/YouTube грузятся, при этом трафик этих приложений и DNS видны в CONNECT-логах прокси.

---

## 13. Итог

| | |
|---|---|
| DNS через DoH+CONNECT в движке | ✅ реализовано, собрано в AAR/APK |
| UDP/QUIC fail-fast | ✅ (лог в APK) |
| IPv6 без утечки маршрутов | ✅ `::/0` сохранён; DNS-конфиг упрощён |
| Тест прокси явно «мимо VPN» | ✅ |
| BUILD | **SUCCESSFUL** |
| Тесты | **157 / 157** |
| SHA-256 | `2af3a307a4f271125b8ef7769c3c149159f3d166418f32f190c17bc2eb6dea6a` |
| APK | `/opt/weather-currency/output/WeatherCurrency-v2.3.apk` |
| Публикация / установка | **не выполнялись** — ждём указаний |
| E2E на устройстве | **не выполнено** (§11–12) |
