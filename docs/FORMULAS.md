# RaskolVault — Формулы и экономическая модель

Документ для разработчиков и серверных администраторов. Все формулы — точные реализации кода (версия 1.2.6-c).

---

## 1. Конвертация валют (ConvertEngine)

```
rate(from, to) = priceOf(from) / priceOf(to)

gross   = amount × rate
feeBase = max(gross × baseFee, minFee)
feeTax  = amount × taxRate          (налог нации-получателя)
net     = gross - feeBase - feeTax

goldFlow = (amount - amount × baseFee) × priceOf(from)   (только для нацвалют)
```

- `baseFee` — per-pair из `rates.yml`, дефолт 2% (`exchange.default-fee`)
- `minFee` — `exchange.min-fee` (анти-dust), дефолт 0.01
- `taxRate` — `TaxService.getConvertRate(nation)` если `to` национальная, иначе 0
- Блокировки: `from == to`, `amount < minAmount`, `amount > maxTx`, эмбарго пары, резерв нации исчерпан, недостаточно средств

Резервные движения при конверте:
```
если from национальная:  reserveDebit(fromNation, goldFlow)
если to   национальная:  reserveCredit(toNation, goldFlow)
при откате — обратные движения
```

---

## 2. Биржа ордеров (ExchangeOrderService)

```
fee = amount × defaultFee            (2%, сжигается = sink)
net = amount - fee
tax = amount × exchangeRate          (налог нации-получателя, в казну)
```

- Налог собирается через `TaxService.collectExchange()`
- `net` уходит продавцу
- Авто-матчинг: при создании ордера ищутся встречные с пересекающейся ценой

---

## 3. Аукцион (AuctionService)

### Создание лота
```
refPrice   = (type == AUCTION) ? startPrice : buyoutPrice
listingFee = max(listingFeeMin, refPrice × listingFeeRate)
```
Списывается **после** всех проверок (ban → blacklist → limit → balance). При любом отказе до списания деньги не теряются.

### Ставка (bid)
```
minNext = base + max(1.0, base × 0.05),  base = max(currentBid, startPrice)
```
Снайпинг: если `timeLeft < snipingWindowMillis` → `expiresAt += snipingExtendMillis`,
но не больше `createdAt + 30 дней` (абсолютный cap).

### Продажа (buyout / победа на торгах)
```
sellFee   = finalPrice × sellFeeRate        (3%, платформе, сжигается)
nationTax = finalPrice × auctionRate        (дефолт 2%, настраивается королём)
net       = finalPrice - sellFee - nationTax
```
- `nationTax` → `ReserveBank.treasuryUuid(nation)` продавца
- `net` → продавцу
- Репутация: `reputation.incrementSuccess(seller)`

### Ограничения
- `bidHistory` cap = 50 последних записей
- `maxLotsPerPlayer` = 10 активных
- `blacklistItems` — материалы, запрещённые к продаже

---

## 4. Банк (BankService)

### Вклады
```
accrue(days, rate) = principal × rate × days / 365
cap: accrued ≤ principal × 2.0        (200% от тела)
```
Ставки (дефолты, настраиваются per-nation):
| Срок | Ставка |
|---|---|
| DEMAND | 1% |
| TERM_7 | 2% |
| TERM_30 | 3.5% |
| TERM_90 | 5% |

Досрочное закрытие срочного вклада: `penalty = principal × earlyPenaltyRate` (дефолт 2%), проценты сгорают; вкладчику выплачивается `principal - penalty`, и именно эта фактическая выплата списывается из пула. Штраф остаётся в пуле банка.

Закрытие вклада до востребования или вклада после maturity: `interestPaid = min(accrued, availableInterestReserveAfterOlderClaims)`, `interestPayable = accrued - interestPaid`. Тело возвращается при достаточной ликвидности пула. Невыплаченная часть фиксируется в `bank_interest_payables` и обслуживается FIFO по мере появления процентного резерва. Досрочное закрытие срочного вклада до maturity по-прежнему не создаёт процентного обязательства.

### Кредиты
```
required_collateral = amount × collateralRatio        (дефолт 1.2 = 120%)
rate_adjusted       = baseLoanRate + creditBonus(player)
creditBonus         = -min(0.02, score × 0.002) + max(0, -score × 0.005)

accrueTo(now):
  days    = (min(now, dueAt) - lastAccrualAt) / 86400000
  accrued += principal × rate × days / 365

outstanding(now) = principal + accrued   (principal уже уменьшается при погашении)

applyRepayment(pay):
  interestPart  = min(pay, accrued)
  principalPart = pay - interestPart
  accrued      -= interestPart
  principal    -= principalPart   (но не ниже 0)
  repaid       += pay
```

### Фракционное резервирование
```
maxLoans(nation) = reserveOf(nation) × reserveMultiplier      (дефолт 3.0)
totalOutstandingLoans(nation) = Σ (loan.outstanding × priceInGld(loan.currency))
```
Условие выдачи: `totalOutstandingLoans + amount × priceInGld ≤ maxLoans` И `pool ≥ amount`.

### Bank run защита
При `closeDeposit` demand: если `pool < principal` → отказ «недостаточно ликвидности».

### Взыскание и реализация залога (просрочка)

1. Кредит переводится в `RECOVERY`; начисление процентов замораживается.
2. Залог переводится в `HELD`. Оценочная стоимость не считается выручкой и не зачисляется в пул.
3. При подтверждении фактической реализации:
```
toInterest = min(recoverableInterest, actualProceeds) → interestReserve
toPrincipal = min(recoverablePrincipal, actualProceeds - toInterest) → pool
surplus = max(0, actualProceeds - toInterest - toPrincipal) → borrower
uncoveredDebt = remaining principal + accrued interest → RECOVERY
```
4. Недостаток остаётся долгом заёмщика; частичное и полное погашение доступны в `RECOVERY`.
5. Новый кредит запрещён, пока у заёмщика есть непогашенный долг после взыскания.
6. Залог, который ещё не реализован, возвращается при полном погашении долга.

Оценка `PLEDGED/HELD` не включается в денежные активы. В банковском балансе показывается отдельно только справочная оценка лотов `HELD`.

### Кредитная история
```
creditScore = repaidCount - 2 × defaultedCount
```
Персистится в таблице `bank_credit` SQLite и переживает рестарты.

### Возврат залога оффлайн-игроку
Если игрок оффлайн при погашении — предмет помещается в durable-очередь `bank_pending_returns` SQLite и выдаётся при `PlayerJoinEvent` с квитанцией в PDC.

---

## 5. Налоги (TaxService)

Четыре независимых налога, cap 5% (`MAX_RATE`):
| Налог | Триггер | Получатель |
|---|---|---|
| `convert` | конвертация в нацвалюту | казна нации |
| `exchange` | исполнение ордера биржи | казна нации |
| `market` | ChestShop/ESGUI в городе | казна нации |
| `auction` | продажа на аукционе | казна нации |

`DailyReport` хранит разбивку: `convert`, `exchange`, `market`, `auction`, `total`. Сброс — `resetDaily()`.

---

## 6. Золотой стандарт (ReserveBank)

```
priceOf(national)  = min(parity, reserve / supply)
priceOf(global)    = 1.0
coverage(nation)   = reserve / (supply × parity)
coverageFloor      = 0.5        (ниже = КРИЗИС + broadcast)
parityRange        = [0.5, 2.0]
withdrawDailyShare = 0.25       (лимит вывода из резерва в сутки)
seigniorage        = 0.02       (сеньораж при минте, сжигается)
```

---

## 7. Инварианты (EconomicInvariantAuditor)

Ежечасная самодиагностика (`audit.interval-minutes`):
1. `Σ balances[currency]` сходится с резервом и sink'ами
2. `Σ outstanding_loans_in_GLD ≤ reserveOf(nation) × reserveMultiplier`
3. банковские активы и обязательства сверяются отдельно по каждой валюте
4. отрицательный собственный капитал банка (`assets < liabilities`) отмечается как FAIL
5. удерживаемый залог не включается в денежные активы до фактической реализации
6. WAL-checkpoint OK
7. `writer.failed == 0` за последний интервал

Формулы банковского баланса и ограничения сверки: [BANK_BALANCE_SHEET.md](BANK_BALANCE_SHEET.md). Сверка является агрегированным snapshot-анализом, а не полноценным double-entry general ledger.

При провале — WARN в лог.

---

## 8. PlaceholderAPI (1.2.6-c)

```
%raskolvault_balance_<CUR>%        баланс в валюте (напр. %raskolvault_balance_gld%)
%raskolvault_auction_active%       активных лотов игрока
%raskolvault_auction_reputation%   репутация продавца
%raskolvault_bank_deposits%        сумма активных вкладов (GLD)
%raskolvault_bank_deposits_count%  количество вкладов
%raskolvault_bank_loans%           сумма остатков кредитов (GLD)
%raskolvault_bank_loans_count%     количество кредитов
%raskolvault_bank_credit_score%    кредитный скор
%raskolvault_nation%               нация (Towny) или "—"
```

---

## 9. Анти-эксплойт механизмы

| Механизм | Защита от |
|---|---|
| sniping cap (30 дн) | бесконечного продления лота |
| bidHistory cap (50) | раздувания YAML |
| accrued cap (200%) | бесконечного накопления demand-вкладов |
| bank run check | увода pool в минус |
| self-convert block | потери комиссий на GLD→GLD |
| `wallets.has()` check | отрицательных балансов |
| `SafeStorage.saveAtomic()` | повреждения YAML при падении |
| `pendingReturns` | потери предметов оффлайн-игроков |
| `maxLotsPerPlayer` | спама лотами |
| `blacklistItems` | продажи админ-предметов |
| listing fee после проверок | потери комиссии при отказе |
| кредит под залог той же валюты — блок | циклического залога |

---

## 10. Конфиг-ключи (сводка)

```yaml
exchange:  default-fee, min-fee, min-amount, confirm-timeout-seconds, rates-file
safety:    rate-limit.*, pay-rate-limit.*, max-transaction
reserve:   coverage-floor, parity-min/max, tax-max, withdraw-daily-share, seigniorage
auction:   listing-fee-rate/min, sell-fee-rate, max-duration-hours, max-lots-per-player,
           sounds-enabled, chat-notify, sniping-window-ms, sniping-extend-ms, blacklist-items
bank:      defaults.{demand-rate, rate-7, rate-30, rate-90, loan-rate, collateral-ratio,
           reserve-multiplier, early-penalty}, accrual-interval-minutes,
           liquidation-check-minutes, appraisal.<MATERIAL>, nations.<нация>.*
audit:     interval-minutes
```
