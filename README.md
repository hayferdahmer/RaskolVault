<div align="center">

# ⚜ RASKOL VAULT

**Многовалютное экономическое ядро для Paper 1.21**
*Сервер «РАСКОЛ | ДВЕ КОРОНЫ»*

`v1.2.6` · `Java 21` · `Paper 1.21.4` · `SQLite (WAL)` · `Proprietary`

---

> Золотой стандарт · Биржа ордеров · Аукцион · Банк · Налоги · Казны наций

</div>

---

## Обзор

RaskolVault — экономический слой, заменяющий одно-валютную модель на **систему национальных валют с золотым обеспечением**. Глобальная валюта `GLD` проксируется в EssentialsX; каждая нация Towny может выпускать собственную валюту, обеспеченную резервом казны.

```
┌────────────────────────────────────────────────────────────┐
│                        RASKOL VAULT                          │
├──────────────┬──────────────┬──────────────┬───────────────┤
│   КОШЕЛЁК    │    ОБМЕН     │    БИРЖА     │    АУКЦИОН    │
│  WalletService│ ConvertEngine│ OrderService │ AuctionService│
├──────────────┼──────────────┼──────────────┼───────────────┤
│    БАНК      │   НАЛОГИ     │    КАЗНЫ     │   РЕЗЕРВ      │
│  BankService │  TaxService  │ NationTreasury│  ReserveBank  │
├──────────────┴──────────────┴──────────────┴───────────────┤
│              SQLiteLedger (WAL) + SafeStorage              │
└────────────────────────────────────────────────────────────┘
```

---

## Модули

| Модуль | Назначение | Ключевые команды |
|---|---|---|
| **Кошелёк** | Балансы, переводы (≤6 блоков), история | `/rv wallet`, `/rv pay` |
| **Обмен** | Конвертация валют с комиссией и налогом | `/rv convert`, `/rv confirm` |
| **Биржа** | P2P-ордера на продажу/покупку валют | `/rv exchange` |
| **Аукцион** | Лоты предметов: торги / buyout, снайпинг-защита | `/rv auction` |
| **Банк** | Вклады (процент), кредиты (залог), ликвидация | `/rv bank` |
| **Кабинет** | Управление нацией: резерв, налоги, ставки | `/rv cabinet` |
| **Казны** | Резерв нации, золотой стандарт, покрытие | — |
| **Налоги** | convert / exchange / market / auction | — |

---

## Экономическая модель

Полные формулы — в [`docs/FORMULAS.md`](docs/FORMULAS.md). Кратко:

- **Цена нацвалюты** = `min(parity, reserve / supply)`
- **Покрытие** = `reserve / (supply × parity)`; `< 50%` → КРИЗИС
- **Конвертация** = `gross − feeBase − feeTax`
- **Кредит** = залог `150%`, ставка `10% ± creditScore`
- **Фракционное резервирование** = `reserve × 3.0`

---

## Команды

| Команда | Описание | Доступ |
|---|---|---|
| `/rv wallet` | GUI кошелька | все |
| `/rv pay <ник> <валюта> <сумма>` | Перевод (дистанция ≤6 блоков) | все |
| `/rv convert <из> <в> <сумма>` + `/rv confirm` | Обмен | все |
| `/rv rates` | Таблица курсов | все |
| `/rv exchange …` | Биржа ордеров | все |
| `/rv auction …` | Аукцион | все |
| `/rv bank …` | Вклады / кредиты | все |
| `/rv cabinet` | Кабинет правителя | король/мэр |
| `/rv guide` | Справка | все |
| `/rv admin …` | Админ-блок | `raskolvault.admin` |

---

## PlaceholderAPI

```
%raskolvault_balance_<CUR>%   %raskolvault_auction_active%
%raskolvault_auction_reputation%   %raskolvault_bank_deposits%
%raskolvault_bank_loans%   %raskolvault_bank_credit_score%
%raskolvault_nation%
```

---

## Установка

1. Собери: `mvn clean package` → `target/RaskolVault-1.2.6.jar`
2. Положи в `plugins/`
3. Зависимости (soft): EssentialsX, Towny, LuckPerms, PlaceholderAPI
4. Рестарт → `/rv guide`

---

## Эксплуатация

Ранбук (деплой, бэкапы, restore, откат, инциденты) — [`OPERATIONS.md`](OPERATIONS.md).

## История изменений

[`CHANGELOG.md`](CHANGELOG.md)

---

## Лицензия

**RASKOL Proprietary License v1.0** © 2026 hayferdahmer.
Все права защищены. См. [`LICENSE`](LICENSE).

---

<div align="center">

**РАСКОЛ · ДВЕ КОРОНЫ** · экономика, которая держится на золоте

</div>
