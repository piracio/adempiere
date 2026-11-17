# Issue #4411: immediate Average Invoice costing regressions

This suite exercises negative inventory, replenishment, invoice matching and
accounting period boundaries on an idle, disposable GardenWorld clone. It is
not a certification of every costing method/dimension. Bulk regeneration has
a bounded, caller-transaction contract described below. Use Java 11 and a fresh JVM with no concurrent application on the test clone.

## Provision a disposable clone

Back up and verify the source seed before cloning. Do not run against a shared
or production database. On the clone, configure schema 101 for manual period
control; January and February of the configured test year must be open. Disable counter creation for
the selected document types (120, 122, 123, 124, 126, 132 in the tested seed). Set
client 11 CLIENT_ACCOUNTING to Q so the tests explicitly post documents.
Require exactly one active Material cost element. The tested seed had duplicate
Material elements 100 and 1000000; only the duplicate 1000000 was deactivated
on the clone. These are fixture preconditions, not production migrations.
Use an independently verified properties file pointing to this clone.
Install migration 10355 before TEST42, which exercises the public process against
its committed GW-AIC-READY and GW-AIC-AVG data on this disposable clone.

`adempiere.costtest.database.accountingYear` optionally selects the four-digit
fixture year (default 2022). GardenWorldCleanup advances seed dates during a
normal deployment; inspect the clone's calendar, tax validity and price-list
versions and select an existing open year instead of rewriting source dates.
The final local rerun used 2025 on a clone of the user's migrated installation.
The existing Ant database-property forwarding also forwards this optional key.
The harness fails on missing preconditions rather than provisioning a database.

## Explicit database identity

All five JVM/Ant properties are mandatory, without whitespace, wildcards or
unresolved placeholders:

- `adempiere.costtest.database.jdbcUrl`: exact PostgreSQL JDBC URL with one host,
  explicit port and database; only the literal `?encoding=UNICODE` option is allowed.
- `adempiere.costtest.database.serverAddress`: literal server IP; Java may connect
  to IPv4 even when a separate client resolves localhost to IPv6.
- `adempiere.costtest.database.serverPort`: exact port, also matching the URL.
- `adempiere.costtest.database.databaseName`: exact database, also matching the URL.
- `adempiere.costtest.database.user`: both session_user and current_user must match.

Configuration is validated before ADempiere startup. Actual JDBC metadata and a
read-only server identity query are checked after startup and again for each
fixture and rollback-verification transaction. Startup/pool initialization
necessarily precedes actual server verification. Identity checking is not a
server-side write restriction or evidence that a database is disposable.
Use a separately enforced runtime boundary restricting filesystem/network access.
Proxies, role switching, failover URLs and other URL options are unsupported.

## Run

The compile target builds the modified production classes and both test classes
so these regressions do not silently use an old installed costing engine:

```sh
ant -f extend/CostingEngine/build.xml test-negative-inventory-identity
ant -f extend/CostingEngine/build.xml test-negative-inventory-integration \
  -Dcosttest.properties=/absolute/path/Adempiere-clone.properties \
  -Dadempiere.costtest.database.jdbcUrl=jdbc:postgresql://localhost:5432/clone?encoding=UNICODE \
  -Dadempiere.costtest.database.serverAddress=127.0.0.1 \
  -Dadempiere.costtest.database.serverPort=5432 \
  -Dadempiere.costtest.database.databaseName=clone \
  -Dadempiere.costtest.database.user=adempiere
```

Quote the JDBC URL if the shell expands `?`. Repository-local build properties
must define `adempiere.trunk` and `adempiere.home`. Override `build` and
`test.build.dir` to use an isolated output directory. The compile/test targets
have no init, clean or dist dependency. The standalone target
`run-negative-inventory-test` takes `-Dcosttest.case=TEST01` through TEST35 and
defaults to TEST03. It requires the same explicit configuration.

## Coverage

There are 86 identity validation cases and 65 integration invocations:

| Case | Behavior checked |
| --- | --- |
| TEST01 | Closed January sale, February replenishment and invoice; January cost/fact rows unchanged |
| TEST02 | Two same-day negative issues, receipt and invoice; quantity, value and event metadata |
| TEST03 | Missing M_Cost dimension created once; repeated lookup preserves persisted price |
| TEST04 | Open-period negative crossing and replenishment; 10 units valued at 400 |
| TEST05 (4) | Partial cover, exact cover, lower price and unchanged price |
| TEST06 | Receipt and earlier-chain replay preserve balances and detail count |
| TEST07 | Balanced posting; inventory 400, COGS 600, adjustment 100 |
| TEST08 | Two successive replenishments; uncovered units keep provisional cost |
| TEST09 | Receipt reversal restores the previous negative balance |
| TEST10 (3) | Higher/lower invoice price, partial invoice followed by remaining invoice |
| TEST11 | Invoice reversal restores receipt valuation and accounting |
| TEST12 | Closed invoice match prevents deleting facts of its open-period receipt |
| TEST13 | Invoice after receipt period closes; open-period adjustment, historical rows unchanged |
| TEST14 | Reject replay before changing closed successor sequence/Processing/audit fields |
| TEST15 | Direct MatchPO reversal-cost entry point resolves its own original match |
| TEST16 | Bulk preflight rejects closed history without changing costs or facts |
| TEST17 | Bulk rebuild includes boundary-day matches and can be reposted with correct balances |
| TEST18 | Failure on a second product rolls back the complete batch to its savepoint |
| TEST19 | Closed invoice match prevents resetting its open receipt in bulk |
| TEST20 | Incomplete costing tail is rejected before reset |
| TEST21 | Quantity aggregation isolates accounting schemas for the same product/ASI |
| TEST22 (2) | Reject zero-cost shipments with no history or positive unvalued stock |
| TEST23 | Permit negative stock with a known cost from a prior receipt; post COGS |
| TEST24 (2) | Reject posting legacy zero or missing accounting cost details |
| TEST25 | Sum lower-level and optional components before rejecting zero/empty totals |
| TEST26 | Second zero-cost line rejects shipment; caller rollback restores both lines |
| TEST27 | Deferred costing cannot bypass posting or regeneration checks |
| TEST28 | Regenerating only a parallel non-accounting type does not validate an unselected accounting type |
| TEST29 (3) | Select a February landed cost on a January receipt, allocate sold/remaining stock and repeat without changes |
| TEST30 | Vendor credit reduces inventory/expense with the opposite sign |
| TEST31 | Two successive costs and replay preserve balances; only open invoices may need reposting |
| TEST32 | Closed landed-cost invoice rejects processing before changing rows |
| TEST33 | Closed successor period prevents a replay and preserves its posted facts |
| TEST34 | Preserve known unit estimate after stock exhaustion and a zero-quantity cost event |
| TEST35 | Rebuild open-period physical movements twice without losing the earlier-receipt landed cost |
| TEST36 (4) | Closed-period replenishment at equal/lower price and partial/exact coverage; all closed facts/details immutable |
| TEST37 (3) | Invoice after further shipment, with open/closed history and another receipt; weighted remaining-stock contribution |
| TEST38 (2) | Invoice after an inter-warehouse transfer, with open/closed source history; transfer is not consumption at client level |
| TEST39 | Public setShipLine create-from-receipt API preserves ASI; invoice price adjustment and forced reposting reconcile |
| TEST40 | Actual landed-cost invoice completion and DocAction reversal; preserve all closed types including parallel Standard |
| TEST41 | Complete dual-USD-schema calculation/posting; one-schema rebuild and reposting of affected documents in both schemas |
| TEST42 | Framework-owned process transaction with committed XML examples and MPInstance; rollback second-product failure, then regenerate/repost twice |
| TEST43 (4) | Cost precision threshold, combined lower-level fraction and negative nonzero cost |
| TEST44 (4) | Late freight after full/partial receipt consumption and replacement, with distinct/reused ASI; inventory, expense and closed history |

The fixed costing key is client 11, schema 101, cost type 50000, default Material,
client-level organization/warehouse/ASI zero. Values and precision are asserted
in addition to physical quantities. Posting must produce nonempty, balanced
facts. Historical comparisons include full rows and audit metadata.

## Reversibility

Most fixtures create tagged products, documents, orders and product prices in a
named transaction and roll back on success or failure. TEST41 commits owned
secondary-schema configuration for legacy account lookups, then removes that
configuration after its business fixture rolls back. Mandatory owned account/org
elements require an ID/schema/client-guarded SQL delete during configuration
cleanup; no pre-existing schema is deleted.
TEST42 intentionally tests SvrProcess with its own transaction and persisted
MPInstance: its successful regenerations commit on the disposable clone and can
replace open cost/fact IDs and audit fields. It uses only the two named XML
examples, restores their temporary category and deletes its own process records.
The injected failure must preserve full business-row hashes before those
successful runs. This test is not a claim that every test leaves the entire
clone byte-identical, and it must never run on the user's working database. Document-only fixtures own a
partner/address. Posting fixtures use the clone's existing Joe Block partner
because legacy account resolution reads partner accounts outside the fixture
transaction. Complete seed partner rows (including balances) are snapshotted and
verified after rollback, along with M_Cost, document types, schemas, warehouse,
locator, price lists, products, periods, controls and tax rows. Owned records and
related costing, storage, match and fact rows must be absent after rollback.
Period cache state and global Env context are restored. Run sequentially.
Rollback failures are propagated; a connection still active after failure is
discarded rather than closed through a potentially committing Trx.close().
Record-ID sequence allocation can commit independently and is not reversible by
fixture rollback. Use a disposable clone even if restoration checks pass.

## Zero sales cost rule

For stocked customer shipments with nonzero quantity, the accounting cost must
be nonzero at the schema costing precision. This is an explicit business rule,
not a statement that IFRS mandates Java exceptions. `ZeroSalesCostException`
identifies product, shipment line, schema, cost type and accounting date.
This rule affects applicable accounting cost types across clients, including
Standard; it is not limited to the GardenWorld examples or Average Invoice.

The immediate engine validates after computing all elements/types for a schema.
Posting validates each line using its applicable accounting type. Bulk regeneration
validates the accounting type only if it was selected. Optional zero components
and parallel non-accounting cost types do not independently block the shipment.
Standard amount/lower-level/adjustment fields are summed at existing costing
precision; no currency conversion or global rounding policy changes here.

The document API can return false after `PO.save()` catches the exception. The
MA shipment path now preserves the specific error text instead of substituting
only a generic material-transaction message. Callers must check the return value
and roll back failed document transactions. TEST22/26 explicitly roll back to a
fixture savepoint and verify cost/movement/storage restoration. There is no new
implicit commit or global transaction rollback inside the cost validator.

Deferred costing may complete a physical document before computing costs; its
posting and the accounting-cost regeneration must still reject zero/missing costs.
Receipts, customer returns, reversal lines and zero-quantity lines are outside
this shipment rule; their full lifecycle coverage remains separate. TEST40 adds
one real landed-cost invoice/reversal lifecycle; it does not cover every return
or reversal permutation.

TEST02 now establishes a real prior receipt cost and exhausts that opening stock
before its two negative issues. It adds two setup movements and retains final
stock 7/value 280/average 40. It is not proof of arbitrary economic ordering in #171.
A manually assigned M_Cost price alone was not consumed by the tested Average
Invoice path without prior detail history; that separate behavior was not changed.

## Late landed costs on earlier receipts

The bounded generator selects completed invoice allocations by their own invoice
DateAcct when their receipt predates the requested range. It does not rebuild
that closed receipt. Before writes it checks the source dimensions, own period,
allocated quantity/base, and the full selected costing tail. It serializes the
existing cost dimensions in product/schema/type/element order; this is not a full
certification of concurrency with live document processing.

This path currently handles Average Invoice and exactly one physical receipt
transaction. Split/serial receipt transactions are rejected rather than applying
the full allocation repeatedly. TEST44 follows the original receipt's retained
contribution through full/partial consumption before replacement, including a
replacement that explicitly reuses the same ASI. Later receipts do not restore
an exhausted contribution. Issues consume it proportionally within the ASI pool;
client-level transfers are excluded from consumption. This adds historical reads
per later issue; large-history performance has not been measured. Other mixed
receipt/return and invoice-price/landed-cost cascades require dedicated work.

A zero-quantity cost detail links to C_LandedCostAllocation, receipt and invoice
line. The allocation total is converted to schema currency before splitting
inventory/expense, avoiding unit-first rounding. Invoice posting uses the same
total; the economic effect is posted through the existing vendor invoice.
No new tables or fiscal documents are introduced.

Repeated selection skips an existing source/schema/type/element detail after
locking; duplicate legacy details fail for reconciliation. This assumes completed
source amounts are immutable. Detecting or repairing direct changes to source
amounts or wrong existing details is not certified. Explicit forced replay uses
the earlier state, propagates later open landed-cost events and rejects closed
successors. The caller must roll back a failed direct replay. It may need to
repost successor invoices in open periods after a successful forced replay.

TEST29 creates a real vendor charge invoice and then persists an allocation
awaiting costing (an import/recovery scenario). It tests no, partial and complete
sale of the receipt before closure. This does not certify the whole landed-cost
allocation wizard or full invoice reversal lifecycle. TEST30 is a vendor credit,
not a reversal workflow. The APC type (124) must also have counter creation off
on the disposable clone; its original setting was Y and is recoverable.

All current financial fixtures use GardenWorld USD. Chile, New Zealand, Mexico,
Colombia and Argentina are planned validation jurisdictions, not certified tax
localizations or currency test results in this suite.

## Boundaries requiring further work

- GenerateCostDetail now requires a bounded date range and an existing caller
  transaction. It preflights the whole selection before deleting/resetting,
  rejects closed source/match periods and excluded related matches, requires the
  complete transaction/detail tail, and restores a savepoint on exceptions.
  It never commits per product. Callers must roll back after failure.
  TEST16-20 exercise this entry point without MPInstance or an independently
  committing SvrProcess wrapper. Non-transaction manufacturing collectors are
  explicitly rejected until they have a safe replay contract. This changes the
  process transaction/locking size; measure large datasets separately.
  TEST42 additionally exercises the public SvrProcess/MPInstance entry point.
  The separate legacy AverageInvoiceCostTest imports the retired
  org.eevolution.process.GenerateCostDetail class, which is absent from this
  checkout/runtime. It is not included in the 151-invocation result and was not
  modified as part of this change; its broader modernization is separate work.
- A successor in a closed period now rejects immediate replay before sequence
  renumbering/Processing changes. The caller must roll back the triggering
  transaction; this is a rejection policy, not automatic cross-period reconciliation.
- The engine's ASI quantity aggregation now includes the schema and applicable
  organization/warehouse dimension. TEST21 isolates two schemas, and TEST41
  covers one complete dual-USD-schema costing/posting chain. Warehouse/org isolation,
  lot/serial flows, lower-level costs, manufacturing, landed costs and credit
  notes still need broader integration coverage. The old unscoped public helper
  is deprecated and retained for binary compatibility.
- TEST37/38 now exercise later issues, another receipt and a client-level transfer
  before invoicing. Closed-receipt invoice events use the immutable provision and
  carry its contribution proportionally through the average-cost pool; only the
  retained share adjusts inventory, while posting expenses the remainder in the
  open period. No closed receipt or successor is replayed. Customer returns in
  that closed-receipt consumption trace currently require explicit reconciliation
  and are rejected; serialized/split receipts are also rejected. Organization,
  warehouse and return-specific tracing still needs dedicated coverage.
- No timestamp migration is included. Business dates and creation/completion
  metadata represent different events; same-day deterministic replay remains a
  separate requirement. A transaction ID is not a business-event timestamp.
- Site validators, triggers and external services may add effects not covered
  by the listed snapshots. No blanket rollback guarantee is claimed.
- TEST41 covers two schemas in USD, not currency conversion. Margin-based
  commission adjustments, foreign-exchange effects, a complete new adjustment
  event model and economic ordering independent of same-day execution order are
  not certified by this suite. A successful cost-cycle regression is not evidence
  that every requirement in the original #4411 feature request is implemented.

## Final local verification

The final pass compiles the changed production sources and tests into an isolated
output directory, then runs all 151 JUnit invocations against a restored copy of
local `adempiere`. The test login has no UPDATE permission on working-database
M_Cost and no INSERT permission on working-database M_Storage. A restrictive Java
policy separately denies host writes, private/environment credential reads,
external connections, subprocesses and disabling isolation. Inspect the saved
report for the actual outcome; the test count alone is not a pass guarantee.
No source dump, deployment JAR, working database or Git history is modified by
the regression run. The production source changes still require normal build,
review and deployment before the running application consumes them.
