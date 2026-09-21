# RaskolVault — OPERATIONS (ранбук 1.1.5)

## 1. Ежедневный осмотр (2 мин)
1. `/rv admin health` — сверь: TPS ≥ 19.5, writer failed = 0, queue < 100, hit-rate ≥ 90%.
2. Консоль: нет WARN «writer queue > 500» и «writer failed +N».
3. `/spark tps` — 20.0.

## 2. Бекапы
- Авто: ежедневно 04:00 в `plugins/RaskolVault/backups/`, ротация 7 дней.
- Ручной: `/rv admin backup` → файл `manual-<ts>.sqlite`.
- Внешний (рекомендуется): cron/rsync папки `plugins/RaskolVault/` раз в сутки.

## 3. Restore (дрел)
1. `/rv admin backup` (свежий снапшот перед операцией).
2. `/rv admin restore <имя_файла.sqlite>` → создаёт `restore.flag`.
3. Рестарт сервера → на старте база заменена до инициализации пула, флаг удалён, в логе WARN «RESTORE применён».
4. Проверка: `/rv admin balance <ник>` — данные из бэкапа.

## 4. Конфиг-валидатор
На старте клампит опасные значения и логирует WARN-отчёт. Клампы не сохраняются в файл — действуют на сессию. Границы: pool-size 1..16, coverage-floor 0.1..1, parity-min 0.01..1, parity-max 1..100, tax-max 0..0.2, seigniorage 0..0.5, default-fee 0..0.5.

## 5. Кризис покрытия
- Coverage < 100% → цена = резерв/эмиссия (девальвация), broadcast нации.
- Coverage < coverage-floor (0.5) → КРИЗИС, broadcast, конверты из валюты закрываются при исчерпании резерва.
- Лечение: `/rv bank deposit` (золото в резерв) или `/rv admin burn` (сжечь эмиссию).

## 6. Эскроу (фундамент 1.2.0)
API: `EscrowService.hold(owner, currency, amount, ticket)` / `release(ticket, to)` / `refund(ticket)`.
Персистентность: таблица `escrow`. Используется будущей биржей/аукционом/облигациями.

## 7. Soak-тест (перед ланчем)
1. `/rv admin simulate-load 50 5000`.
2. 30 мин наблюдения: `/rv admin health` каждые 5 мин, `/spark profiler` 2 мин в середине.
3. Критерий: TPS ≥ 19.5, queue < 500, failed = 0, hit-rate ≥ 90%.

## 8. Аварийные сценарии
| Симптом | Действие |
|---|---|
| writer failed растёт | стоп, `/rv admin restore` из последнего бэкапа |
| queue > 500 постоянно | поднять `storage.sqlite.pool-size` до 8, рестарт |
| цена нации = 0 | резерв пуст → `/rv bank deposit` или `/rv admin reserve set` |
| рассинхрон кэш↔БД | подождать reconcile (30 мин) или рестарт (кэш греется из БД) |
