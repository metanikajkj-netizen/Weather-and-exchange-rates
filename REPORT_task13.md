# REPORT — TASK №13 — Proxy VPN (HTTP/HTTPS) для WeatherCurrency v2.2

Исполнитель: MiMo Code
Дата: 2026-11-05
Проект: `/opt/weather-currency`
APK: `/opt/weather-currency/output/WeatherCurrency-v2.2.apk`
Release: https://github.com/metanikajkj-netizen/Weather-and-exchange-rates/releases/tag/v2.2

---

## 1. Подготовка и документация

| Файл | Статус |
|---|---|
| `PROJECT_STATE.md` / `NEXT_SESSION.md` / `DECISIONS.md` | **отсутствуют** |
| `REPORT_task12.md` | прочитан (v2.1, WorkManager 15 мин) |
| Версия до задачи | **2.1 / versionCode 12** |
| Целевая версия | **2.2 / versionCode 13** |
| Keystore | **не изменён** (mtime 2026-11-04 21:09) |

## 2. Аудит VPN-движков (обязательный)

| Критерий | tun2socks v2.7.0 (xjasonlyu) | sing-box v1.14.4 |
|---|---|---|
| HTTP CONNECT | ✅ `proxy/http` (Basic auth) | ✅ outbound type `http` |
| HTTPS-прокси (TLS до прокси) | ✅ **добавлено нами** (scheme `https` + TLS Client, сертификаты проверяются) | ✅ |
| Proxy-Authorization | ✅ Basic | ✅ |
| Android VpnService | ✅ `fd://<tun_fd>` (in-process AAR) | ⚠️ нужен libbox/JNI; CLI без fd |
| arm64-v8a | ✅ собран `libgojni.so` (23 МБ) | ✅ официальный android-arm64 |
| DNS | ✅ Go resolver → через прокси-dialer; UDP DNS приложений — см. ограничения | ✅ |
| UDP / QUIC | ❌ HTTP proxy: `DialUDP → ErrUnsupported` (fail-closed) | ✅ (не наш выбор) |
| Защита от циклов | ✅ `addDisallowedApplication(own)` + in-process dialer | ✅ protect() |
| Лицензия | **MIT** | GPLv3 + ограничение на название |
| Сборка в текущем окружении | ✅ **собран AAR** (Go 1.26 + NDK r27c через qemu-x86_64 + gomobile) | ❌ не собирали (GPL, сложнее) |
| NDK / SDK | NDK r27c скачан в `/opt/android-ndk-r27c`; SDK уже был | — |

**Выбран движок: tun2socks (MIT)** — собран в AAR `app/libs/tun2socks.aar` (8.0 МБ, classes + `jni/arm64-v8a/libgojni.so`).

Собственный TCP/IP-стек **не писался**.

### Патч исходников tun2socks (Apache/MIT — модификации разрешены)
- `proxy/http/http.go`: регистрация схемы `https` + обёртка `tls.Client` c **проверкой сертификатов** (`InsecureSkipVerify` не используется), `MinVersion=TLS1.2`.

### Сборка AAR (среда Termux/proot, aarch64)
- Go 1.26.10 linux-arm64, NDK r27c (x86_64 host tools под qemu-x86_64 + патч gomobile `archNDK` для linux/arm64)
- `gomobile bind -target=android/arm64 -androidapi=26 ./engine` → **exit 0**

## 3. Архитектура (выбранная)

```
Приложение (WeatherCurrency)
├── UI: Navigation Drawer → «Proxy VPN»
├── Настройки: DataStore + шифрование пароля Android Keystore (AES-256-GCM)
├── Тест: TCP → [TLS] → HTTP CONNECT → TLS к www.gstatic.com → GET /generate_204
└── VPN: ProxyVpnService (VpnService)
      ├── Builder: 172.19.0.1/32 + ::/126, маршруты 0.0.0.0/0 + ::/0, DNS 8.8.8.8
      ├── addDisallowedApplication(own) — трафик своего приложения мимо VPN (анти-петля)
      ├── establish() → TUN fd
      └── engine.Key(device="fd://N", proxy="http(s)://…") → Engine.start()
            └── tun2socks netstack: TCP → CONNECT через прокси
```

## 4. Ограничения HTTP/HTTPS-прокси (явно в UI)

- **Только TCP через CONNECT.** UDP/QUIC **не поддерживаются** движком при HTTP-прокси → пакеты отбрасываются (fail-closed), не «полноценный VPN для всего устройства».
- **DNS других приложений** через TUN (UDP) может не работать — задокументировано в карточке «Ограничения».
- **Собственный трафик WeatherCurrency** идёт мимо VPN (защита от петли) — задокументировано.
- IPv4 + IPv6 маршруты; IPv6 через CONNECT — если прокси не поддерживает, соединение не устанавливается (без тихого обхода).
- TLS к HTTPS-прокси: сертификат **проверяется**, отключение проверки **не используется**; перехват HTTPS / свои CA не внедряются.

## 5. Изменённые / новые файлы

| Файл | Изменение |
|---|---|
| `app/build.gradle.kts` | versionName **2.2**, versionCode **13**, `implementation(files("libs/tun2socks.aar"))` |
| `AndroidManifest.xml` | `ProxyVpnService` (BIND_VPN_SERVICE, foregroundServiceType=specialUse), FOREGROUND_SERVICE* |
| `proxy/ProxyConfig.kt` | **новый** — модель, валидация, `toProxyUrl()` |
| `proxy/ProxyCrypto.kt` | **новый** — Keystore AES-GCM для пароля |
| `proxy/ProxySettingsStore.kt` | **новый** — DataStore + зеркало для сервиса |
| `proxy/ProxyTester.kt` | **новый** — тест CONNECT + latency, классификация ошибок |
| `proxy/ProxyVpnService.kt` | **новый** — VpnService + tun2socks, foreground notification |
| `proxy/ProxyVpnViewModel.kt` | **новый** — состояние UI, prepare(), broadcast-статус |
| `ui/ProxyVpnScreen.kt` | **новый** — статус, настройки, тест, подключение, ограничения |
| `MainActivity.kt` | drawer «Proxy VPN», EXTRA_OPEN_PROXY_VPN, VPN permission launcher |
| `app/libs/tun2socks.aar` | **новый** движок (MIT + наш патч https) |
| `test/ProxyConfigTest.kt` | **новый** — 12 тестов |
| `test/ProxyConnectProtocolTest.kt` | **новый** — 7 тестов (MockWebServer CONNECT) |

**Не менялись:** погода, валюты, криптовалюты, OTA-механизм (GitHub URL, WorkManager 15 мин), SHA-256/подпись APK, packageName, keystore.

## 6. Сборка и тесты

```bash
$ ./build.sh :app:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL

$ ./build.sh :app:testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL in 1m 37s
```

| | |
|---|---|
| Тесты | **153 / 153 passed**, failures=0, errors=0 |
| Новые | ProxyConfigTest 12, ProxyConnectProtocolTest 7 |
| Регрессии | 134 прежних зелёные |

Покрытие: валидация настроек, URL, Base64-auth, CONNECT 200/407/405, отказ соединения, маппинг ошибок (DNS/таймаут/TLS/auth) на уровне логики.  
**Не unit-покрыто:** Keystore на устройстве, реальный прокси, TUN/engine E2E.

### Проверка Manifest / разрешений (badging)
```
package: com.weathercurrency.app  versionCode='13' versionName='2.2'
INTERNET, ACCESS_NETWORK_STATE, LOCATION×2, REQUEST_INSTALL_PACKAGES,
POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE,
+ merge WorkManager (WAKE_LOCK, BOOT_COMPLETED…)
```

## 7. Параметры APK

| | |
|---|---|
| BUILD | **SUCCESSFUL** |
| package | `com.weathercurrency.app` |
| versionName / versionCode | **2.2 / 13** |
| Подпись | **SIGNATURE_VALID** |
| Сертификат v2.1 == v2.2 | **CERTS_IDENTICAL** (`90eaea49…`) |
| Поверх v2.1 | **да** (13>12, same package, same cert) |
| Размер | **36 195 548** bytes (вкл. libgojni.so) |
| SHA-256 | **`cb4b6150e9e63002718d718021ba70cf8e989deb7fea0c33fde41438a007ed0b`** |
| Путь | `/opt/weather-currency/output/WeatherCurrency-v2.2.apk` |

## 8. SSH / SCP / публикация

```text
SSH → SSH_OK (ключ /root/.ssh/id_ed25519)
SCP → сначала timed-out на 36 МБ; доведено rsync --partial → 36 195 548 bytes
VPS: sha256sum -c → WeatherCurrency-v2.2.apk: OK
gh release view v2.2 → RELEASE_ABSENT
gh release create v2.2 … → exit 0
  https://github.com/metanikajkj-netizen/Weather-and-exchange-rates/releases/tag/v2.2
```

### Проверки после публикации

| Проверка | Результат |
|---|---|
| Release v2.2 | ✅ существует |
| draft / prerelease | ✅ false / false |
| 3 вложения | ✅ APK, SHA256SUMS.txt, UPDATE_META.txt |
| SHA скачанного APK | ✅ `cb4b6150…ed0b` |
| UPDATE_META versionCode | ✅ **13** |
| `/releases/latest` | ✅ **tag=v2.2** |

**Ссылка:** https://github.com/metanikajkj-netizen/Weather-and-exchange-rates/releases/tag/v2.2

## 9. Что подтверждено автоматически vs что нужно на устройстве

**Подтверждено unit-тестами / сборкой / инспекцией:**
- 153 теста (валидация прокси, CONNECT-протокол, вся прежняя функциональность)
- Внедрение AAR, разрешения манифеста, подпись, versionCode
- Отсутствие `InsecureSkipVerify` в исходниках; Keystore-шифрование пароля
- Анти-петля через `addDisallowedApplication` (в коде VpnService)
- Дедуп/настройки OTA не тронуты

**Требует реального испытания на Android с рабочим прокси (не выполнялось):**
1. Системный диалог `VpnService.prepare()` и establish TUN
2. Фактический трафик других приложений через CONNECT (браузер)
3. Тест «Тестирование» с вашим прокси (407/таймаут/TLS)
4. Поведение DNS/UDP в реальной сети (задокументированные ограничения)
5. Конфликт с другим активным VPN, отзыв TUN (`onRevoke`)
6. Doze / выключение экрана при активном VPN

**Не заявляется:** полноценная подтверждённая работа VPN только по сборке — **не подтверждена на устройстве**.

## 10. Ограничения

1. APK на телефон **не устанавливался**.
2. Реальный прокси-сервер для E2E **не предоставлялся**.
3. AAR только **arm64-v8a** (как устройство пользователя; x86_64 эмулятор не поддерживается).
4. Размер APK вырос (~36 МБ) из-за встроенного движка.

## 11. Изоляция

```bash
$ find /opt/pump-tracker /root/pump-tracker /opt/vpnautoswitch-ru -xdev -mmin -240
(пусто)
```

Keystore не изменён. Сторонние проекты не трогались. Секреты SSH/токены в отчёт не выносились.

---

## ИТОГ — **ПУБЛИКАЦИЯ ВЫПОЛНЕНА**

| Этап | Статус |
|---|---|
| Аудит движков (tun2socks/sing-box) | ✅ выбран tun2socks (MIT), AAR собран |
| versionName/versionCode = 2.2 / 13 | ✅ |
| Реальный VpnService + tun2socks (fd://, http/https) | ✅ в коде |
| Тест прокси (CONNECT, TLS, auth) | ✅ в коде + unit 19 новых |
| Keystore-пароль, анти-петля, ограничения UI | ✅ |
| BUILD SUCCESSFUL | ✅ |
| Тесты | **153 / 153** |
| Сертификат v2.1 == v2.2 | ✅ |
| SHA-256 | `cb4b6150e9e63002718d718021ba70cf8e989deb7fea0c33fde41438a007ed0b` |
| SCP + sha256sum на VPS | ✅ OK |
| GitHub Release v2.2 | ✅ **опубликован** |
| `/releases/latest` → v2.2, versionCode 13 | ✅ |
| VPN на реальном Android | **не проверено** (см. §9) |
