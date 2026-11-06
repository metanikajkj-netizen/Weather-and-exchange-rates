# REPORT — TASK №17 — Углублённая диагностика Proxy VPN v2.3

Исполнитель: MiMo Code  
Дата: 2026-11-05  
Проект: `/opt/weather-currency` (WeatherCurrency **v2.3**, versionCode 14)  
Режим: **STRICT READ-ONLY** для исходников/конфигурации проекта (код не менялся, APK не пересобирался, релиз не публиковался).

---

## 0. Подготовка и документация

| Файл | Статус |
|---|---|
| `PROJECT_STATE.md` / `NEXT_SESSION.md` / `DECISIONS.md` | **отсутствуют** |
| `REPORT_task13.md`, `REPORT_task14.md`, `REPORT_task15.md`, `REPORT_task16.md` | **изучены** |

**Симптомы (от пользователя, v2.2 и v2.3 одинаково):** Telegram работает; Chrome не загружает сайты; YouTube не грузит данные.  
v2.3 добавил DoH через HTTP CONNECT — проблема сохраняется.

---

## 1. Фактически выполненные проверки

### 1.1 Диагностика Android на этой машине

| Проверка | Результат |
|---|---|
| Среда | Android **15** (MIUI V816), модель **23013PC75G**; Ubuntu **proot** внутри Termux |
| ADB (platform-tools) | найден; **x86_64** — под qemu; `adb devices` → **0 устройств**; daemon-старт в proot нестабилен (`cannot connect to daemon`) |
| **logcat** | **доступен**: `/system/bin/logcat` → читает `main/system/events/crash` |
| Установлено `com.weathercurrency.app` | **НЕТ** (`pm list packages` → 281 пакет, weathercurrency **0**; `pm path` — пусто) |
| Активный VPN | **нет** (`dumpsys connectivity` — VPN-трафика не видно) |
| logcat: `weathercurrency` / `ProxyVpnService` / `[DNS]` / `DoH via` | **0 совпадений** за всю доступную историю |
| `dumpsys package` / `settings` (private_dns и т.п.) | **Permission Denial** (нет DUMP / INTERACT_ACROSS_USERS) |
| APK в `/sdcard/Download/` | есть `WeatherCurrency-v2.3.apk` (файл), **но приложение не установлено** |

**Вывод (факт):** на **данном** устройстве (build-среда) нет установленного WeatherCurrency и нет сессии Proxy VPN → **собрать реальные логи TUN/DoH/CONNECT с падающего Chrome/YouTube невозможно**.  
Тестирование пользователя, вероятно, выполнялось на **другом устройстве** либо приложение было удалено после теста.

### 1.2 Что удалось проверить локально

| Проверка | Результат |
|---|---|
| DoH-протокол (POST `application/dns-message`) до `cloudflare-dns.com` и `dns.google` **с этой сети** | ✅ **HTTP 200, rcode=0 (NOERROR)** для `google.com` |
| Код движка в APK v2.3 (`libgojni.so` strings) | ✅ `[DNS] %s -> DoH via proxy`, `[DNS] DoH failed…`, `[UDP] … dropped (proxy has no UDP support…)` |
| Исходники tun2socks (только чтение) | просмотрены (см. §8) |

---

## 2. Подтверждённые причины (по коду + логике симптомов)

### F1 — TCP через CONNECT работает (вывод из симптомов + код)

**Факт (пользователь):** Telegram работает с активным Proxy VPN.  
**Факт (код):** весь TCP идёт `handleTCPConn` → `Proxy().DialContext` → HTTP CONNECT (`tunnel/tcp.go:26–34`, `proxy/http/http.go:79–127`), включая Basic-auth при необходимости.

⇒ **Общий канал TCP+CONNECT функционален.** Отказ Chrome/YouTube **не объясняется** тотальным отказом CONNECT.

### F2 — DNS UDP/53: перехват реализован (v2.3), E2E **не подтверждён**

**Факт (код):**
- `tunnel/udp.go:31–33` — при `DstPort==53` → `handleDNSUDP` **до** `DialUDP`;
- `tunnel/dns.go:46–76` — чтение query из TUN, DoH, `WriteTo(resp, from)`;
- `dns.go:109` — DoH-соединение: **`t.Proxy().DialContext` → CONNECT к `1.1.1.1:443` / `8.8.8.8:443`**, TLS `ServerName` cloudflare/dns.google, **без** `InsecureSkipVerify` (`dns.go:114–118`);
- gvisor: первый UDP-пакет передаётся в endpoint через `ep.HandlePacket` (`forwarder.go:114`) → `ReadFrom` получает query; `DstPort = LocalPort` = порт назначения = 53 (маппинг `udp.go:23–26`).

**Факт:** протокол DoH **работает** из сети сборки (§1.2).  
**Нет факта** (нет логов устройства): что именно вижут `[DNS] … -> DoH via proxy` / `DoH failed` / `write response` при отказе Chrome.

### F3 — «Тест прокси» ≠ тест VPN (уже исправлено в v2.3, поведение сохранено)

`addDisallowedApplication(packageName)` (`ProxyVpnService.kt:94`) — сокеты приложения (включая движок и кнопку теста) **не входят в TUN**. Успех «Тест прокси» **не доказывает** DNS/TCP для Chrome.

### F4 — Telegram: сильное объяснение (гипотеза, не доказана логами)

Telegram MTProto активно использует **встроенные IP-адреса дата-центров** (149.154.160.0/20 и др.) и сохранённые сессии — **резолв имён для базового соединения не обязателен**. В коде WeatherCurrency **нет** ничего, что помогало бы Telegram точнее Chrome.  
⇒ Работа Telegram **не подтверждает** исправность DNS; согласуется с F1 (TCP ок) + сломанный DNS для доменных имён (Chrome/YouTube).

---

## 3. Анализ DNS — что сломано/рискованно (код)

| # | Пункт | Статус |
|---|---|---|
| D1 | Перехват UDP/53 в `handleUDPConn` | ✅ код v2.3 |
| D2 | Резолв через прокси (DoH + CONNECT) | ✅ код; локальный DoH-протокол ✅; **на устройстве не видели** |
| D3 | Ответ в тот же TUN-flow (`WriteTo` исходному адресу) | ✅ код (`dns.go:72`); валиден для gonet (`fullToUDPAddr` → `*net.UDPAddr`) |
| D4 | Корректность IP/UDP checksum | **не проверено на устройстве** (считает gvisor при `ep.Write`; подтверждение — только пакетный дамп/logcat) |
| D5 | A/AAAA/NXDOMAIN/таймауты | wire-format DoH — семантика полная; таймауты 12 c flow / 6 c DoH (`dns.go:25,124`) |
| D6 | DNS-loop | DoH через `Proxy().DialContext` наружу к прокси; наш UID мимо TUN; DoH не опрашивает TUN-резолвер |
| D7 | IPv6 DNS | в v2.3 серверы **только IPv4** `8.8.8.8`, `1.1.1.1` (`ProxyVpnService.kt:92–93`); перехват по порту 53 одинаков для v4/v6 |
| D8 | TCP/53 | **не перехватывается** — идёт обычным `handleTCPConn` → **CONNECT к :53**; многие HTTP-прокси **запрещают** CONNECT на порт 53 → TCP-DNS упадёт (факт политики прокси, не кода) |
| D9 | Private DNS (DoT :853) / Secure DNS Chrome | **вне нашего UDP/53-перехвата**; TCP/853 → CONNECT; если прокси режет 853 — DoT сломан. **Значение private_dns на устройстве не прочитано** (нет прав `settings`) |

### Наиболее вероятные причины сохранения отказа Chrome/YouTube (ранжирование)

1. **H-DNS-1 (высокая):** DoH-CONNECT `1.1.1.1/8.8.8.8:443` **отклонён или не проходит** у пользовательского прокси (политика портов/хостов) → лог `[DNS] DoH … failed` → без ответа → Chrome молчит.  
2. **H-DNS-2 (высокая):** система/Chrome используют **DoT :853 или DoH Chrome**, а не UDP/53 → наш перехват не срабатывает; CONNECT :853 запрещён прокси → отказ.  
3. **H-DNS-3 (средняя):** ответ DoH пишется, но не доходит до сокета клиента (особенность `WriteTo(addr)` vs `Write(nil)` в штатном релее `udp.go:67` — **в релее ответ в origin пишется с `to=nil`**, у нас `from`) — риск, **нужны логи** `[DNS] write response`.  
4. **H-QUIC (средняя):** DNS ок, но Chrome зависает на QUIC (`[UDP] … dropped`) и не успевает/не переключается на TCP.  
5. **H-MIUI (низкая–средняя):** MIUI dual-DNS/ускорение сети может уводить DNS мимо VPN — **проверить settings нельзя без прав**.

---

## 4. Анализ TCP

| Пункт | Результат |
|---|---|
| CONNECT к сайтам (443/80) | код корректен; **косвенно подтверждён** работой Telegram |
| TLS после CONNECT | end-to-end клиент↔сервер внутри туннеля; код не ломает |
| Авторизация прокси | Basic в CONNECT (`http.go:101`) |
| Таймауты | OkHttp клиента приложения отдельно; движок — `tcpConnectTimeout` 5 c (`tunnel/tunnel.go`) |
| Зависания TCP | `tcpWaitTimeout` 60 c на half-close (`tunnel/tcp.go:72`) |
| MTU/MSS | TUN **1500** (`ProxyVpnService.kt:85`); PMTU-проблемы **не подтверждены** (нет дампа); для DNS (малые пакеты) не актуальны |

**Не подтверждено на устройстве:** конкретные CONNECT-ответы прокси для chrome.com / googlevideo.com.

---

## 5. Анализ UDP/QUIC

| Пункт | Результат |
|---|---|
| UDP/53 | перехват → DoH (v2.3) |
| UDP/443 (QUIC) | `DialUDP` → `ErrUnsupported` → **немедленный drop** (`udp.go:36–43`), лог `[UDP] … dropped … QUIC` |
| Реакция Chrome | **не гарантирована** (может ждать QUIC-timeout); **не предполагаем**, что переход на TCP всегда быстрый |
| Зависание в движке | drop без ожидания — зависание возможно только **на стороне клиента** |
| Инкапсуляция UDP в TCP | **не выполняется** (протокольно честно) |

---

## 6. Анализ IPv6

| Пункт | Результат |
|---|---|
| Маршруты | `0.0.0.0/0` + **`::/0`** (`ProxyVpnService.kt:87–89`) — IPv6 **не обходит** VPN |
| DNS-серверы v2.3 | только IPv4 (`8.8.8.8`, `1.1.1.1`) — IPv6-DNS-сервер убран (Task15) |
| TCP/IPv6 | через CONNECT (если прокси поддерживает) |
| UDP/IPv6 | fail-closed (как IPv4) |
| Утечки | прямого IPv6-маршрута мимо TUN **нет в коде**; фактические соединения не снимались |

---

## 7. Почему Telegram работает (вывод)

**Подтверждено:** Telegram в VPN-сессии использует TCP → наш CONNECT (F1).  
**Объяснение (сильная гипотеза, логов Telegram нет):** клиент полагается на встроенные IP DC / кеши, домены не критичны для основного канала.  
**Важно:** это **не** доказательство исправности DNS — Chrome/YouTube требуют резолв почти каждого запроса.

---

## 8. Конкретные файлы и строки (проблемные/ключевые)

| Файл:строки | Роль |
|---|---|
| `/opt/tun2socks/tunnel/udp.go:31–33` | перехват DNS; `:36–43` drop QUIC; `:67` **штатный релей пишет origin c `to=nil`** |
| `/opt/tun2socks/tunnel/dns.go:29–36,46–76,109–124` | DoH upstream, handler, CONNECT+TLS, `WriteTo(resp,from)` |
| `/opt/tun2socks/proxy/http/http.go:83–84,87–127` | DialUDP=unsupported; CONNECT+auth |
| `/opt/tun2socks/tunnel/tcp.go:26–34` | TCP→CONNECT |
| `/opt/weather-currency/.../ProxyVpnService.kt:85–94` | MTU, маршруты, DNS, `addDisallowedApplication` |
| gvisor `udp/forwarder.go:114` | первый пакет → endpoint (`HandlePacket`) |

Сравнение релея vs DNS-handler записи ответа:

```text
udp.go:67  unidirectionalPacketStream(origin, remote, nil, "remote->origin"…)  // to=nil
dns.go:72  uc.WriteTo(resp, from)                                             // to=client addr
```

Оба валидны для gonet; **различие требует проверки на устройстве** (H-DNS-3).

---

## 9. Минимальный план исправления (предложение, не выполнялось)

**Шаг 0 — обязательно получить логи** (см. §10). Без них любой фикс слепой.

**Шаг 1 — подтвердить, куда упирается DNS:**
- В логах: есть ли `[DNS] … -> DoH via proxy`?  
  - **нет** → перехват не срабатывает (H-DNS-2/MIUI) → логировать `DstPort`/Private DNS;  
  - **есть + `DoH failed`** → читать текст ошибки (CONNECT отклонён / TLS / таймаут) → H-DNS-1;  
  - **есть + `write response` error** → править запись ответа на `uc.Write(resp)` / `WriteTo(nil)` как в релее (`udp.go:67`);  
  - **DNS в логах OK, но Chrome висит** → `[UDP] dropped` + QUIC → H-QUIC.

**Шаг 2 — вероятные точечные правки (только после §10):**
1. DoH upstream → **только `dns.google:443`** (CONNECT к «веб-хостам» обычно разрешён) либо **DoH по тому же IP/хосту, что уже разрешён** политикой прокси.  
2. Запись ответа: синхронизировать с рабочим релеем (`Write()`/`WriteTo(nil)`).  
3. Доп. обход DoT: перехват **TCP/853** — при отказе CONNECT :853 отвечать ошибкой клиенту быстрее (или форсировать UDP/53 локальным резолвером в движке) — **только если подтверждён DoT в логах**.  
4. Не отключать VPN для Chrome/YouTube.

**Шаг 3 — повторный E2E** на устройстве (Chrome, YouTube, Telegram + логи).

---

## 10. Проверки, невозможные без действий пользователя

На **данной** машине приложение **не установлено**, VPN не активен → нужны действия:

**Вариант A — на телефоне пользователя, где воспроизводится:**
```bash
# На ПК с adb (USB debugging):
adb logcat -c
# включить VPN в WeatherCurrency, открыть Chrome/YouTube
adb logcat -v time | grep -iE "ProxyVpn|\[DNS\]|\[UDP\]|\[TCP\]|DoH|weathercurrency|VpnService" > vpn_diag.txt
adb shell settings get secure private_dns_mode
adb shell settings get secure private_dns_specifier
adb shell dumpsys connectivity | grep -i vpn
```
Прислать `vpn_diag.txt` (без содержимого трафика; **без** строк Proxy-Authorization/паролей — их в коде нет, но grep не выводит body).

**Вариант B — разрешить установку на это устройство** (APK уже лежит `/sdcard/Download/WeatherCurrency-v2.3.apk`):  
тогда я смогу сам снять logcat после включения VPN. **Требуется ваше согласие** (установка + включение VPN = изменение состояния устройства).

Также пригодится: значение Private DNS в настройках Android (пользователь: Настройки → Подключение → Приватный DNS).

---

## 11. Ограничения отчёта

1. **STRICT READ-ONLY** — исходники/APK/релизы не менялись.  
2. Логов падающего Chrome/YouTube/Telegram **нет** (приложение не установлено на этой машине).  
3. Секреты прокси, Proxy-Authorization, содержимое трафика — **не извлекались**.  
4. `settings`/`dumpsys package` — без необходимых прав.  
5. Разделы §3–§7 содержат **факты кода** и **ранжированные гипотезы**; подтверждение на устройстве — §10.

---

## ИТОГ

| | |
|---|---|
| logcat на этой машине | ✅ доступен, но **WeatherCurrency не установлен → логов VPN нет** |
| DoH-протокол с этой сети | ✅ NOERROR (cloudflare + google) |
| TCP CONNECT | ✅ код; косвенно подтверждён работой Telegram |
| DNS DoH-путь | ⚠️ код v2.3 полон, **E2E не подтверждён** — главный кандидат отказа вместе с DoT/политикой прокси |
| QUIC | ✅ fail-fast в коде; реакция Chrome — гипотеза |
| IPv6 | ✅ маршруты закрыты, IPv6-DNS убран; утечек в коде нет |
| Telegram | ⚠️ IP-DC кеширование (гипотеза), **не** индикатор здоровья DNS |
| Следующий шаг | **логи с устройства** (§10 A) или **согласие** на установку сюда (§10 B) |
