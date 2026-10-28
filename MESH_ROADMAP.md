# MESH ROADMAP — план разработки Mesh-модификации Telegram Android

Статус на 07.10.2026: APK собран, запускается на эмуляторе Pixel_9 (Android 15, API 35).
Базовая версия: Telegram 12.7.3, applicationId `org.telegram.mesh`.

## Текущее состояние (что уже есть)

- [x] Mesh-пакет из 9 классов (Nearby Connections, P2P_CLUSTER)
- [x] E2EE: X25519 + AES-256-GCM + HMAC-SHA256
- [x] NetworkSwitcher (ONLINE / OFFLINE / MESH_ONLY / HYBRID)
- [x] Перехват исходящих в SendMessagesHelper.performSendMessageRequest (строка ~7491)
- [x] UI: MeshSettingsActivity + MeshMapActivity, пункт в Settings (id 24)
- [x] Старт из ApplicationLoader.initMeshNetwork
- [x] Разрешения в манифесте (BLUETOOTH_SCAN/ADVERTISE, NEARBY_WIFI_DEVICES и др.)

## Критические пробелы (блокируют сквозной сценарий)

1. **Нет привязки peer ↔ Telegram userId** — MeshPeer создаётся с userId=0,
   findPeerByUserId() никогда не матчит собеседника → перехват не срабатывает.
2. **Входящие mesh-сообщения не попадают в чаты** — уходят только в MeshListener,
   нет инжекта в MessagesStorage/диалоги.
3. **Media-перехват отправляет пустой payload** (нет байтов файла).
4. **onMeshFallback — заглушка**: при ошибке mesh сообщение теряется, а не уходит на сервер.
5. **MeshNetworkService не зарегистрирован** в манифесте → нет фоновой работы.
6. **Фиолетовый индикатор mesh-онлайна не реализован** (требование ТЗ).
7. 6 mesh-событий NotificationCenter объявлены, но не постуются/не слушаются.

---

## ФАЗА 0 — Тестовая инфраструктура (0.5 дня)

Цель: два работающих клиента для сквозных тестов mesh.

- [ ] 0.1 Создать второй AVD (Pixel_9_b) — clone текущего профиля
- [ ] 0.2 Залогинить оба эмулятора под разными тестовыми номерами
      (в debug-сборке виден чекбокс "Test Backend" — можно использовать тестовый DC Telegram)
- [ ] 0.3 Проверить Nearby Connections между двумя эмуляторами
      (эмуляторы не видят друг друга по BLE/WiFi Direct — для теста нужен
      хост-мост: проверка через STRATEGY P2P_CLUSTER на одной машине;
      если не работает — реальные устройства в одной Wi-Fi сети)
- [ ] 0.4 Скрипт-харнесс: adb logcat-фильтр по тегу MeshManager для обоих пиров

Риск: Nearby Connections на эмуляторах может не работать вообще
(нет реального Wi-Fi/BLE). План Б — два физических Android-устройства.

## ФАЗА 1 — Identity binding: peer ↔ Telegram userId (1-2 дня) ★ КРИТИЧНО

Цель: mesh-пир знает, какому Telegram-аккаунту он принадлежит.

- [ ] 1.1 Протокол handshake (новое сообщение TYPE_IDENTITY = 7):
      при установке соединения пир отправляет:
      { node_id, telegram_user_id, display_name, public_key, signature }
      где signature = Ed25519/X25519-подпись over (user_id + node_id) ключом аккаунта
- [ ] 1.2 Проблема доверия: как доказать, что пир с userId=X действительно X?
      Вариант А (простой): после handshake отправить через ОБЫЧНЫЙ сервер Telegram
      одноразовый код в "Избранное"/секретный канал — верификация через сервер.
      Вариант Б (без сервера): Safety Number — сравнение fingerprints вручную
      (как в Signal) — показывать в UI код безопасности сессии.
      Решение: начать с варианта А,指纹 из B показывать в настройках mesh-чата.
- [ ] 1.3 MeshManager.handleIdentityExchange(): заполнить userId у MeshPeer,
      сохранить в таблицу node_bindings (MessagesStorage или отдельный SQLite)
- [ ] 1.4 Кеширование привязок: nodeId ↔ userId переживает рестарт приложения
- [ ] 1.5 Привязка при discovery: MeshMessageInterceptor теперь сможет найти
      пира по userId собеседника чата

Выход: findPeerByUserId(realId) возвращает живого пира.

## ФАЗА 2 — Входящие сообщения в диалоги (2-3 дня) ★ КРИТИЧНО

Цель: mesh-сообщение появляется в чате как обычное, с пометкой.

- [ ] 2.1 MeshInboxProcessor (новый класс): принимает расшифрованное MeshMessage,
      конструирует TLRPC.Message (id = отрицательный локальный id из диапазона
      MessagesController.LOCAL_MARK, dialog_id, from_id, date, message)
- [ ] 2.2 Запись в MessagesStorage.putMessages() + обновление DialogCell/draft-логики
- [ ] 2.3 Пост NotificationCenter.didReceiveNewMessages → чат обновляется в реальном времени
- [ ] 2.4 Пометка mesh-сообщений: message.fwd_from? НЕТ — лучше params.put("mesh", "1")
      или отдельный флаг в MessageObject; иконка/цвет в ChatMessageCell
      (минимально инвазивно: не ломать ванильный рендер)
- [ ] 2.5 Delivery/Read receipts: при получении шлём TYPE_RECEIPT,
      отправитель обновляет статус галочек (✓✓)
- [ ] 2.6 Дедупликация: если сообщение пришло и через сервер, и через mesh —
      по client_message_id не дублировать
- [ ] 2.7 Outgoing queue: mesh-сообщения хранятся локально и синхронизируются
      на сервер Telegram КОГДА ИНТЕРНЕТ ПОЯВЛЯЕТСЯ (mesh-first, server-eventually)
      — иначе история на других устройствах пользователя расходится

Выход: два эмулятора/устройства переписываются через mesh с выключенным интернетом,
сообщения видны в чатах с обеих сторон.

## ФАЗА 3 — Fallback и надёжность (1-2 дня)

- [ ] 3.1 Реализовать onMeshFallback: при ошибке доставки через mesh →
      обычный getConnectionsManager().sendRequest(req, ...) без потери сообщения
- [ ] 3.2 Стратегия выбора канала (уточнить политику):
      HYBRID: mesh-first для 1:1 чатов где оба mesh-юзера, server для остального
      OFFLINE: только mesh + очередь на сервер при возврате онлайн
      MESH_ONLY: только mesh, server-запросы блокируются
- [ ] 3.3 Retry-логика: pendingMessages с экспоненциальным backoff
- [ ] 3.4 Таймаут mesh-доставки (5 сек) → автоматический fallback
- [ ] 3.5 Индикация в UI: какое сообщение ушло через mesh (иконка), какое через сервер
- [ ] 3.6 Восстановление сессий после дисконнекта (re-advertise, re-connect к известным пирам)

## ФАЗА 4 — Фиолетовый индикатор mesh-онлайна (1 день) — требование ТЗ

- [ ] 4.1 Hook в DialogsAdapter/DialogCell: при отрисовке статуса online
      проверять MeshPresenceHandler.shouldShowMeshIndicator(userId)
- [ ] 4.2 Цвет: синий #3fa9f5 → фиолетовый #9C27B0 (или градиент)
- [ ] 4.3 То же в ChatActivity (заголовок чата: "online" → "online (mesh)")
- [ ] 4.4 То же в ProfileActivity
- [ ] 4.5 Обновление по событиям meshPeerUpdated (подключить NotificationCenter-события,
      которые сейчас объявлены, но не используются)

## ФАЗА 5 — Media через mesh (3-5 дней)

- [ ] 5.1 Чанкованная передача файлов через Nearby Payload.Type.FILE (stream)
- [ ] 5.2 MeshFileTransfer: фото (сжатые), голосовые, видео — лимиты (Nearby: ~100+ МБ/мин)
- [ ] 5.3 Прогресс-бар в UI чата (как обычная загрузка)
- [ ] 5.4 Шифрование файлов тем же сессионным ключом (побайтово AES-GCM чанками)
- [ ] 5.5 Полученные файлы → кэш Telegram (FileLoader/Cache), показ в чате

## ФАЗА 6 — Фоновая работа и батарея (2-3 дня)

- [ ] 6.1 Зарегистрировать MeshNetworkService в манифесте + стартовать при
      включённом mesh-режиме (foreground type connectedDevice)
- [ ] 6.2 Авто-старт при потере интернета (NetworkCallback в ApplicationLoader)
- [ ] 6.3 Doze/App Standby: whitelist-подсказка пользователю + JobScheduler периодика
- [ ] 6.4 Battery-профилирование: Nearby STRATEGY → LOW_POWER при фоне,
      P2P_CLUSTER только при активном использовании
- [ ] 6.5 Точная настройка интервалов presence (30 сек → адаптивный: 10 сек в фоне чата, 2 мин в фоне)

## ФАЗА 7 — Карта подключений с реальными данными (1-2 дня) — улучшение

- [ ] 7.1 MeshMapActivity сейчас схематичная (пиры по кругу) — добавить:
      реальный RSSI → расстояние, тип транспорта (Wi-Fi/BLE иконки)
- [ ] 7.2 Multi-hop визуализация: кто через кого ретранслирует
- [ ] 7.3 Интерактив: тап на пира → профиль/чат с ним

## ФАЗА 8 — Multi-hop routing (долгосрочно, 5+ дней)

- [ ] 8.1 Сейчас hopCount/routePath есть, но ретрансляции нет
- [ ] 8.2 Протокол маршрутизации: gossip-анонсы reachability (TYPE_ROUTE_ANNOUNCE есть)
- [ ] 8.3 Таблица маршрутизации: destination nodeId → next-hop endpointId
- [ ] 8.4 TTL сообщений, защита от петель и replay (nonce cache)
- [ ] 8.5 Onion-подобная упаковка для приватности маршрута (опционально)

## ФАЗА 9 — Security hardening (постоянно)

- [ ] 9.1 Заменить ObjectOutputStream-сериализацию на protobuf/flatbuffers
      (Java-десериализация недоверенных данных = RCE-риск!)
- [ ] 9.2 Double Ratchet (Signal protocol) вместо статического сессионного ключа —
      forward secrecy. Библиотека: libsignal или портирование tde2e (уже есть в jni!)
- [ ] 9.3 Verification UI: safety numbers / QR-скан для подтверждения identity
- [ ] 9.4 Rate limiting входящих (анти-DoS)
- [ ] 9.5 Audit: mesh не должен leak-ить метаданные в серверные запросы Telegram
- [ ] 9.6 Опция "mesh-only для конкретных чатов" (приватные диалоги никогда не идут на сервер)

---

## Приоритизация (критический путь)

```
ФАЗА 0 (эмуляторы)  →  ФАЗА 1 (identity)  →  ФАЗА 2 (входящие)  →  ФАЗА 3 (fallback)
        ↓                      ↓                     ↓
   тест-стенд           без этого ничего      MVP работает E2E
                        не работает
```

Параллельно можно: ФАЗА 4 (индикатор — простой), ФАЗА 6.1 (регистрация сервиса — 15 мин).
Позже: ФАЗА 5 (media), ФАЗА 7-9.

## Оценка MVP (сквозной mesh-чат двух устройств): ~5-7 рабочих дней

## Технические решения, требующие подтверждения

1. **Тестовые аккаунты**: использовать Test Backend (тестовый DC Telegram) или
   два реальных номера?
2. **Identity verification**: вариант А (через сервер, просто) или сразу Б (Safety Numbers)?
3. **Синхронизация mesh-сообщений на сервер** (п.2.7): нужна ли? Без неё история
   теряется на других устройствах; с ней — теряется "чистая" децентрализация.
4. **Эмуляторы vs реальные устройства** для тестов Nearby Connections.
