# SLA breach pricing

This document defines how SLAna prices an uptime agreement between two parties. It covers the calculation formulas and their names, how they become the amounts in an agreement's transactions, and how to implement them across the modules. Nothing described here is implemented yet.

## The agreement

Two parties make a bet on the downtime of one service over a fixed period **T**:

| Party | Role in the code | Locks in escrow | Receives |
| --- | --- | --- | --- |
| Provider (us, the operator of the service) | `providerWallet` | **X**, the payout | **Y** if downtime ≤ threshold |
| Customer (the counterparty) | `customerWallet` | **Y**, the premium | **X** if downtime > threshold |

The threshold is a downtime fraction **q** of the period, for example q = 1%, so the downtime allowance is **τ = q · T**. At settlement, each party gets its own stake back plus the other party's stake when it wins. The escrow therefore holds X + Y.

The provider sets **X** from the customer's cost of downtime and the provider's risk capacity. The provider prices **Y** from the probability of a breach, **p**. The formulas below compute p and Y.

## Formula names

| Name | Short name | Computes | Where it runs |
| --- | --- | --- | --- |
| SLAna Incident Extraction | **SIE** | Incidents and observed time from the per-second history | Off-chain, `uptime-service` |
| SLAna Breach Probability | **SBP** | p = P(downtime > τ) | Off-chain, `uptime-service` |
| SLAna Expected Payout | **SEP** | E[payout] for binary or proportional payouts | Off-chain, `uptime-service` |
| SLAna Premium Formula | **SPF** | Y in lamports | Off-chain quote, stored on-chain at creation |
| SLAna Settlement Rule | **SSR** | Who receives what from escrow | On-chain, Anchor program |

SBP, SEP and SPF only produce a **quote**. Settlement (SSR) uses only the measured downtime and the stored terms (X, Y, τ). It never uses p, so a pricing mistake can't change who gets paid.

## Model

Downtime in a period is a compound sum of incidents:

```
D = d₁ + d₂ + … + d_N          breach ⇔ D > τ
```

- **N**: the number of incidents in the period. Given a rate λ, N is Poisson(λT). λ is unknown and is learned from the history.
- **dᵢ**: incident durations. They are independent and lognormal, because outage durations have a heavy tail and a breach usually comes from one long outage.

The model is a negative binomial–lognormal compound distribution. It is evaluated exactly with the Panjer recursion, with no random sampling, so the same input always gives the same p.

## SIE: SLAna Incident Extraction

**Input:** per-second `{time, down}` records over an observed span of **H** seconds. These come from `UptimeQueryService` or, later, from the per-target monitoring history.

1. Find each run of consecutive `down` seconds.
2. Merge runs that are less than **g** seconds apart (default `g = 60`). Flapping counts as one incident.
3. Each merged cluster is one incident, and its duration **dᵢ** is the number of down seconds in it, not its total span.
4. **Output:** n incidents with durations d₁…dₙ, and H.

**Downtime definition.** SIE must count downtime exactly as settlement will. Today, `UptimeQueryService` reports seconds with no record as down, so time when the process was off counts as downtime. If the contract counts that, pricing must count it too. If it does not, exclude missing seconds from both the incidents and H.

**Optional recency weighting** with half-life **L**:

```
wᵢ = 2^(−ageᵢ / L)
n_eff = Σ wᵢ
H_eff = (L / ln 2) · (1 − 2^(−H / L))
```

Use n_eff and H_eff in place of n and H below.

## SBP: SLAna Breach Probability

### SBP-1. Incident count (Gamma–Poisson, negative binomial)

The prior is α₀ incidents per β₀ seconds. The default is α₀ = 1 per β₀ = 30 days, which keeps p above zero when the history has no incidents.

```
λ | history ~ Gamma(shape = α₀ + n, rate = β₀ + H)

N ~ NegBin(r, β)
    r = α₀ + n
    β = T / (β₀ + H)
    E[N] = r · β
```

The negative binomial includes the uncertainty in λ. A shorter history gives a wider spread of N, and that raises p.

### SBP-2. Incident duration (lognormal, shrunk toward a prior)

The prior values are μ₀, σ₀, κ₀ and ν₀. Defaults: μ₀ = ln(300 s), σ₀ = 1.5, κ₀ = ν₀ = 3.

```
ℓᵢ = ln dᵢ
ℓ̄ = mean(ℓᵢ)
s² = sample variance of ℓᵢ          (0 when n < 2)

μ  = (κ₀ · μ₀ + n · ℓ̄) / (κ₀ + n)
σ² = (ν₀ · σ₀² + (n − 1) · s²) / (ν₀ + n − 1)
```

The prior keeps a few short incidents from producing a light tail.

### SBP-3. Discretization

Time is measured in units of **h** seconds, set to the contract's check interval, because downtime can only be measured in those steps.

```
K = ⌊τ / h⌋
f₀ = 0
f_k = Φ((ln(k·h) − μ)/σ) − Φ((ln((k−1)·h) − μ)/σ)      k = 1 … K_max
      (for k = 1 the second term is 0)
```

Φ is the standard normal CDF. Each duration is rounded **up** to whole units, which slightly overstates downtime, so the error is on the safe side. For the binary payout K_max = K. For the proportional payout K_max = K_full (see SEP).

### SBP-4. Panjer recursion for the total downtime S (in units)

The negative binomial belongs to the (a, b, 0) class, so:

```
a  = β / (1 + β)
b  = (r − 1) · β / (1 + β)
g₀ = (1 + β)^(−r)                          (because f₀ = 0)
g_s = Σ_{k=1..s} (a + b · k / s) · f_k · g_{s−k}      s = 1 … K_max
```

Here g_s = P(S = s). Then:

```
SBP:   p = 1 − Σ_{s=0..K} g_s
```

Durations longer than τ never enter the sum, so they count as breaches, which is correct. The cost is O(K_max²). A 30-day contract with 1-minute checks has K = 432, which takes well under a millisecond.

### SBP-Q. Quick-check approximation

With heavy-tailed durations, a breach usually comes from one long outage:

```
p ≈ 1 − exp(−E[N] · (1 − Φ((ln τ − μ) / σ)))
```

This is a lower bound in practice, because it leaves out breaches caused by several medium outages adding up. Use it for sanity checks and test assertions, never for quotes.

## SEP: SLAna Expected Payout

**Binary payout:** the customer receives X if D > τ.

```
SEP-B:   E[payout] = p · X
```

**Proportional payout (recommended):** the payout grows linearly from 0 at τ to the full X at **τ_full** (for example 2 · τ). This avoids the cliff at the threshold, where 1.01% and 0.99% would settle completely differently, and with it most disputes and incentives to manipulate near the boundary.

```
payout(D) = X · min(1, (D − τ) / (τ_full − τ))      for D > τ, else 0

K_full = ⌊τ_full / h⌋
SEP-P:   E[payout] = Σ_{s=K+1..K_full} X · (s − K)/(K_full − K) · g_s  +  X · (1 − Σ_{s=0..K_full} g_s)
```

Run the SBP-4 recursion up to K_full to get the g_s values for SEP-P.

## SPF: SLAna Premium Formula

The customer pays Y only when there is no breach, which has probability 1 − p. The bet is fair when `E[payout] = (1 − p) · Y`:

```
Y_fair = E[payout] / (1 − p)
Y_ask  = Y_fair · (1 + m)
```

The margin **m** covers model error, cost of locked capital and profit. A default of m = 0.3 is a reasonable start.

In the binary case, SPF reduces to `Y_fair = X · p / (1 − p)`.

**On-chain amounts** are integer lamports, and the premium is rounded up in the provider's favor:

```
Y_lamports = ⌈ Y_ask · 10⁹ ⌉        (with X given in SOL)
```

The quote must also return p, the formula inputs and the model version, so either party can check it.

### Choosing X

```
X = customer_cost_per_second_of_downtime × expected_downtime_given_breach
```

Then cap it at the provider's risk capacity. One outage triggers **every** agreement on the same service at once, so the cap applies to the **sum** of X over all open agreements on that service, not to each agreement separately.

## SSR: SLAna Settlement Rule (on-chain)

At the end of the period, the program reads the measured downtime D in units of h, computed from the agreed monitor's observations:

```
binary:        D > K        → customer receives X + Y
               D ≤ K        → provider receives X + Y
proportional:  P = X · min(K_full − K, max(0, D − K)) / (K_full − K)   (integer division)
               D > K        → customer receives P + Y,  provider receives X − P
               D ≤ K        → provider receives X + Y
```

The program never computes p. It uses only D, K, K_full, X and Y, which are stored when the agreement is created.

## Worked example

| Input | Value |
| --- | --- |
| History H | 90 days, n = 6 incidents |
| Durations after shrinkage | μ = ln(15 min), σ = 1.2 |
| Contract | T = 30 days, q = 1% → τ = 432 min |
| Check interval | h = 60 s → K = 432 |
| Prior | α₀ = 1 per 30 days → r = 7, β = 30 / 120 = 0.25, E[N] = 1.75 |
| Payout | X = 100 SOL, proportional variant with τ_full = 2τ (K_full = 864), m = 0.3 |

| Result | Value |
| --- | --- |
| SBP p (Panjer) | **0.741%** (a 200,000-run Monte Carlo gives 0.747%) |
| SBP-Q quick check | 0.446% (lower bound, as expected) |
| Binary: Y_fair / Y_ask | 0.747 SOL / **0.97 SOL** |
| Proportional: E[payout] | 0.278 SOL |
| Proportional: Y_fair / Y_ask | 0.280 SOL / **0.36 SOL** |

## Parameters

| Parameter | Meaning | Default |
| --- | --- | --- |
| `g` | Gap in seconds below which down runs merge into one incident | 60 |
| `L` | Recency half-life (off when not set) | not set |
| `α₀`, `β₀` | Prior on incident rate | 1 per 30 days |
| `μ₀`, `σ₀`, `κ₀`, `ν₀` | Prior on log-duration | ln 300, 1.5, 3, 3 |
| `m` | Margin | 0.3 |
| `τ_full / τ` | Proportional ramp width | 2 |

Pricing and settlement must use the same `h` and the same downtime definition.

## Validity limits

- **Contract too short:** refuse to quote if `T < 100 · h`, because then 1% of the period is shorter than one check. This rules out the frontend's current 30-second demo durations.
- **History too short:** if `H < T`, return the quote with a `lowConfidence` flag.
- **Incentive to cause downtime:** the customer profits from the provider's downtime and could cause it, for example by DDoS. Either the contract excludes attack-caused downtime, or X must stay below the cost of such an attack.
- **Calibration:** backtest by sliding a window of length T over the history and comparing the observed breach rate with predicted p.

## Implementation plan

The work splits into phases with fixed boundaries between them. Phase 0 fixes the shared contracts. After that, phases 1 and 2 can run in parallel, while phases 3–5 depend on earlier ones.

### Phase 0: Contracts (do first)

- `uptime-service`: records `Incident(Instant start, long downSeconds)`, `IncidentHistory(List<Incident> incidents, long observedSeconds)`, `PricingParameters` (the defaults above, bound as `uptime.pricing.*` in `UptimeProperties`), and `BreachQuote(p, expectedPayout, yFair, yAsk, yLamports, n, observedSeconds, r, beta, mu, sigma, expectedIncidents, lowConfidence, modelVersion = "SBP-1")`.
- REST contract: `GET /api/pricing/quote?durationSeconds=&thresholdPct=&checkIntervalSeconds=&payoutLamports=&payout=binary|proportional&fullPayoutPct=` returns a `BreachQuote`. Invalid input returns a 400 `ProblemDetail` through `ApiExceptionHandler`.

### Phase 1: SIE (`uptime-service/.../pricing/IncidentExtractor`)

- A pure function from per-second points to `IncidentHistory`, with gap merging and an optional recency weight.
- Tests:
  - an empty history
  - all seconds up
  - all seconds down
  - runs merged inside the gap and kept separate beyond it
  - single-second incidents
  - incidents at the range edges

### Phase 2: SBP, SEP and SPF (`uptime-service/.../pricing/BreachProbabilityEstimator`)

- A pure class with no Spring dependencies, so it can be tested as plain unit tests.
- Φ is implemented with an `erfc` approximation (|error| < 1.2·10⁻⁷) or taken from Apache Commons Math.
- Tests:
  - the worked example: p ≈ 0.741%, with a tolerance of 0.01 percentage points
  - n = 0 gives 0 < p < 1
  - one incident longer than τ gives a high p
  - p rises when q is lowered
  - p rises as H shrinks for the same incident rate
  - SBP-Q stays below SBP and approaches it for rare long outages
  - SEP-P ≤ SEP-B
  - a negative or zero threshold is rejected
  - `T < 100·h` is rejected
- Optional: a Monte Carlo cross-check with a fixed seed, run only in a slow test profile.

### Phase 3: Endpoint (`uptime-service/.../web/PricingController`)

- Depends on phases 1 and 2. It reads history through `UptimeQueryService` (later, the per-target history from `INTEGRATION_GAPS.md`) and calls the estimator.
- `UptimeServiceApplicationTests`:
  - a quote for seeded records
  - 400 for each invalid parameter
- Update `uptime-service/CLAUDE.md` to describe the endpoint.

### Phase 4: Frontend Create SLA (`frontend/`)

- Depends on phase 3. The Create SLA form requests a quote and shows p, Y_ask and the inputs.
- The provider's stake X and the customer's premium Y become separate fields. Today there is a single `escrowSol`.
- No pricing math in React. Vitest tests with a mocked `/api/pricing/quote`.
- Update `frontend/CLAUDE.md`.

### Phase 5: Anchor program

- Depends on phase 0 terms only.
- **Create instruction:** stores X, Y, K, K_full and h. Locks X from the provider and Y from the customer.
- **Settle instruction:** implements SSR with integer math on D from monitor submissions.
- Tests cover:
  - both outcomes
  - the D = K boundary
  - the proportional cap at K_full
  - rounding

### Acceptance

Each module's suite passes and `scripts/test-all.sh` stays green. The quote stays off-chain and advisory. Settlement never depends on p.

## Cost efficiency and AI assistance

AI can improve the **inputs** to the formulas above and help people **compare and explain** deals. It never computes p, Y or settlement. Those stay deterministic, reproducible and checkable by both parties, and on-chain settlement can't depend on a model's output.

### SCE: SLAna Cost Efficiency

The numbers below are computed with plain formulas from the SBP/SEP/SPF results. AI components read them and never produce them.

Per agreement:

```
Π   = (1 − p) · Y − E[payout] − c_cap · X · T − c_ops        expected profit
ROC = Π / X                                                   return on locked capital
```

- **c_cap**: the annual cost of capital, with T in years. It prices the cost of keeping X locked in escrow.
- **c_ops**: transaction fees, monitoring and infrastructure cost per agreement.

Per service: one outage triggers every agreement on the same service, so they are evaluated together.

```
Exposure_max = Σ X_j                   worst case: every agreement on the service breaches at once
Π_service    = Σ Π_j
```

The value of better reliability comes from re-running SBP/SEP with the incident rate lowered by a fraction δ:

```
V_rel(δ) = Σ_j ( E[payout_j](λ) − E[payout_j](λ · (1 − δ)) )     saved expected payout per period
```

If an improvement that cuts outages by δ costs less than V_rel(δ) per period, invest in reliability. Otherwise raise premiums or reduce X.

### What can be implemented, and when

| Part | Kind | Needs | Can start |
| --- | --- | --- | --- |
| SCE: Π, ROC, exposure, V_rel | Deterministic code | SBP/SEP/SPF (phases 1–2) | **Now**, right after the pricer |
| AI incident classifier | Language model | Logs, alerts and postmortems for each incident window | **Now** for the model and its tests. It becomes useful once incidents are logged with their causes |
| AI deal advisor | Language model with tool use | Quote and SCE endpoints | After SCE |
| SRF: rate forecast | Machine learning model | Months of operating signals from several services (not collected today) | **Collect data now.** Train once a backtest can beat plain SBP |
| AI cost-of-downtime estimate (X) | Language model | Customer profile entered in Create SLA | Any time. A person confirms the result |

`uptime-service` records only up/down per second today. Every AI part except the advisor and the X estimate needs more data. The first practical step is to start storing that data.

### SRF: SLAna Rate Forecast

#### Purpose

SBP-1 assumes the incident rate stays at its historical level. SRF predicts the rate **λ̂** for the coming contract period from operating signals. The forecast enters SBP **only through the prior**, so the formulas, tests and settlement stay unchanged:

```
α₀ = k
β₀ = k / λ̂
```

- **k**: the forecast's confidence, as an equivalent number of observed incidents. It comes from the backtest.
- The historical counts (n, H) still update this prior exactly as in SBP-1.
- If SRF is unavailable, slow or not yet trained, SBP falls back to the default prior.

#### Data

One row per service per day.

- **Features:**
  - deploys and config changes
  - error rate and p95 latency
  - traffic volume
  - incidents reported by upstream dependencies
  - day of week and holidays
  - incidents in the previous 7 and 30 days
- **Labels:** incident count and total down seconds on the following day, taken from SIE.

#### Model

Start with a Poisson regression (GLM) as the interpretable baseline. Move to gradient-boosted trees with a Poisson objective, or to a survival or hazard model, only if they beat it on the backtest. Incident durations keep the SBP-2 lognormal fit. Forecasting durations can come later.

#### Evaluation

Backtest with a rolling start date: train on everything before day t and predict the next period, repeating for each t.

- Score the count prediction by Poisson deviance.
- Score the resulting p with Brier score and log loss on real breaches, and check calibration.
- Promote a model only if it beats plain SBP, with the default prior, on these scores.
- The backtest also sets k.

#### Module

A new `forecast-service/` module (Python, scikit-learn or LightGBM) with its own `CLAUDE.md` and test suite:

- **Offline training job:** writes a versioned model file.
- **`GET /forecast?serviceId=&from=&to=`:** returns `{ lambdaPerSecond, k, modelVersion, features }`.
- **`uptime-service`:** calls it with a short timeout and falls back to the default prior on failure. The returned values and `modelVersion` go into the `BreachQuote`, so anyone can reproduce the quote without calling the model again.

#### Tests

- Model training and prediction on small synthetic data with a fixed seed.
- The endpoint contract.
- In `uptime-service`, a mocked forecast client covering:
  - success
  - timeout
  - an invalid response
  - each falling back to the default prior where expected

### AI incident classifier (language model)

- **Input:** one SIE incident with its logs, alerts and any postmortem text.
- **Output:** structured JSON `{ cause: OWN_FAULT | ATTACK | DEPENDENCY | PLANNED_MAINTENANCE | UNKNOWN, confidence, evidence[] }`.
- **Review:** a person reviews each classification before it is stored, because it decides whether downtime counts. SIE then:
  - drops excluded causes, such as attacks, if the contract excludes them
  - feeds the cause labels to SRF as features
- **Tests:** the language model client is mocked at the module boundary. They cover:
  - output that fails the schema
  - an unknown cause
  - an API timeout
  - the review state

### AI deal advisor (language model with tool use)

- **Tools:** the language model can call only `getQuote`, `getEvaluation` (SCE) and `getServiceExposure`.
- **Task:** explore variants (threshold, margin, binary or proportional payout, X), then return a structured recommendation that cites the tool results it used.
- **Numbers:** they must come from tool results. The response check rejects any figure that doesn't appear in a tool result.
- **Role:** advisory only. A person approves every deal, and the Create SLA form shows the deterministic quote alongside the advice.

### Implementation phases (continuing the plan above)

- **Phase 6, SCE** (`uptime-service`, after phase 2): `pricing/CostEfficiencyCalculator` and `GET /api/pricing/evaluation`. Unit tests cover:
  - Π and ROC for the worked example
  - exposure across several agreements on one service
  - V_rel falling as δ approaches 0
- **Phase 7, signal collection** (now, separate from the pricer): record deploys, error rate, latency and incident causes for each service. No AI involved.
- **Phase 8, incident classifier and deal advisor:** a server-side AI integration that keeps the API key on the server. Language model calls are mocked in every test. These two can run in parallel with each other.
- **Phase 9, SRF** (`forecast-service/`, needs months of data from phase 7): the baseline model, then the backtest, then the forecast endpoint, then the prior hookup in `uptime-service`. Add its test command to `scripts/test-all.sh` and the module to the root `CLAUDE.md`.

Acceptance for these phases: every AI output is recorded with its model version and inputs, every AI dependency has a deterministic fallback, and no AI output reaches settlement.
