# Building Adempiere Tests from Scratch

## Tutorial Projection: Negative Inventory Costing Test

### Purpose

This document captures the methodology used to build a real Adempiere test case from scratch. The example is the negative inventory costing issue, where the costing engine does not generate the expected cost adjustment when inventory goes below zero and later recovers.

The goal is to teach developers how to move from a very basic Java test runner to a business-meaningful failing test that can guide a future fix.

---

## Phased Approach

```text
Know Your Dragon      -> Understand and reproduce the problem
Slaying the Dragon    -> Implement the smallest safe fix
Prove the Kill        -> Validate the fix with tests
Claim Your Victory    -> Clean up, document, commit, and prepare PR
```

This document focuses on the first phase: **Know Your Dragon**.

---

## 1. Know Your Dragon

### Business Problem

Negative inventory costing does not generate the expected cost adjustment when inventory becomes negative and later returns to positive stock.

In Chilean accounting/business language, the relevant costing method is commonly known as:

```text
promedio ponderado
```

In Adempiere costing terms, this maps to weighted average / average invoice costing behavior.

### Expected Test Result

```text
Expected Adjustment = 100
Actual Adjustment   = 0
FAIL
```

At this stage, the goal is not to fix the logic. The goal is to reproduce and observe the failure.

---

## 2. Start with a Minimal Java Runner

### File

```text
extend/CostingEngine/src/test/java/org/adempiere/NegativeInventoryCostAdjustmentTest.java
```

### First Version

```java
package org.adempiere;

public class NegativeInventoryCostAdjustmentTest {
  public static void main(String[] args) {
    System.out.println("=== START Negative Inventory Cost Test ===");
  }
}
```

### Goal

This proves:

- the file exists in the correct package
- the class compiles
- Ant can run it
- no database or Adempiere complexity has been introduced yet

---

## 3. Add Ant Target Isolation

### File

```text
extend/CostingEngine/build.xml
```

### Compile Target

```xml
<target name="compile-negative-inventory-test"
    description="compile negative inventory costing test">
  <javac debug="false" includeantruntime="false" destdir="${build}">
    <src path="${src}"/>
    <src path="${adempiere.trunk}/base/src"/>

    <include name="test/java/org/adempiere/NegativeInventoryCostAdjustmentTest.java"/>
    <include name="org/adempiere/engine/AverageInvoiceCostingMethod.java"/>

    <classpath refid="lib.class.path"/>
  </javac>
</target>
```

### Run Target

```xml
<target name="run-negative-inventory-test"
    depends="compile-negative-inventory-test, prepare-negative-inventory-test-data"
    description="run negative inventory costing test scaffold">
  <java classname="org.adempiere.NegativeInventoryCostAdjustmentTest"
      fork="true"
      failonerror="true">
    <classpath>
      <pathelement location="${build}"/>
      <path refid="lib.class.path"/>
    </classpath>
  </java>
</target>
```

### Key Lesson

The test build directory must appear before `Adempiere.jar` in the runtime classpath:

```xml
<pathelement location="${build}"/>
<path refid="lib.class.path"/>
```

This allows temporary test-compiled classes and instrumentation to be used during the test run only.

---

## 4. Fix Classpath Incrementally

### Example Problem

```text
java.lang.NoClassDefFoundError: io/vavr/Function4
```

### Cause

The custom Ant test target did not include all runtime dependencies.

### Lesson

When running Adempiere from a custom test target, the classpath must include:

- `Adempiere.jar`
- package jars
- runtime dependencies

Do not start debugging business logic until the runtime classpath is correct.

---

## 5. Bootstrap Adempiere Startup

### Example Problem

```text
SEVERE: No Database Connection
Table Name Not Found - M_Product
```

### Cause

The local `Adempiere.properties` file was pointing to the wrong database/system.

### Checkpoints

A healthy startup should show:

```text
Ini.loadProperties: /Users/hmiranda/Adempiere.properties
DB_PostgreSQL.getDataSource
HikariDataSource - Starting
HikariDataSource - Start completed
```

### Lesson

Before debugging application logic, verify that the test is connected to the intended database.

---

## 6. Add Context Setup

### Method

```java
private void setupContext() {
  Properties ctx = Env.getCtx();

  Env.setContext(ctx, "#COST_DEBUG", "Y");
  Env.setContext(ctx, "#AD_Client_ID", 11);
  Env.setContext(ctx, "#AD_Org_ID", 11);
  Env.setContext(ctx, "#AD_User_ID", 101);
  Env.setContext(ctx, "#M_Warehouse_ID", 103);

  System.out.println(">>> Context initialized for GardenWorld");
}
```

### Lesson

Use one context style consistently:

```java
Properties ctx = Env.getCtx();
Env.setContext(ctx, ...);
```

Avoid mixing:

```java
Env.setContext(Env.getCtx(), ...);
Env.setContext(ctx, ...);
```

Even if both refer to the same context today, explicit context usage is easier to reason about and easier to isolate later.

---

## 7. Add Transaction Handling

### Pattern

```java
String trxName = Trx.createTrxName("CostTest");
Trx trx = Trx.get(trxName, true);

try {
  trx.start();

  // test logic

  trx.commit();
} catch (Exception e) {
  trx.rollback();
  throw new RuntimeException(e);
} finally {
  trx.close();
}
```

### Lesson

Every database-backed test should make transaction boundaries explicit.

---

## 8. Query Business Objects

### Product Query

```java
MProduct product = new Query(
    Env.getCtx(),
    MProduct.Table_Name,
    MProduct.COLUMNNAME_Value + "=?",
    trxName)
  .setParameters("Oak")
  .first();
```

### Cost Query

```java
MCost cost = new Query(
    Env.getCtx(),
    MCost.Table_Name,
    "M_Product_ID=?",
    trxName)
  .setParameters(product.getM_Product_ID())
  .first();
```

### Useful Output

```text
Product found
CurrentCostPrice
CumulatedQty
CumulatedAmt
```

### Lesson

Print observable state before making assertions. It confirms that the test is reading the expected data.

---

## 9. Execute a Real Adempiere Process

### Process

```text
AD_Process_ID = 53223
Name          = Generate Cost Transaction
Class         = org.eevolution.manufacturing.process.GenerateCostDetail
```

### Required Parameter

`DateAcct` is mandatory.

### ProcessInfo and MPInstance Pattern

```java
ProcessInfo processInfo = new ProcessInfo("Generate Cost Transaction", 53223);
processInfo.setAD_Client_ID(Env.getAD_Client_ID(Env.getCtx()));
processInfo.setAD_Org_ID(Env.getAD_Org_ID(Env.getCtx()));
processInfo.setAD_User_ID(Env.getAD_User_ID(Env.getCtx()));

MPInstance instance = new MPInstance(Env.getCtx(), 53223, 0);
instance.saveEx();

MPInstancePara para = new MPInstancePara(instance, 10);
para.setParameter("DateAcct", Timestamp.valueOf("2021-07-25 00:00:00"));
para.setParameter("DateAcct", Timestamp.valueOf("2022-01-25 00:00:00"), true);
para.saveEx();

MPInstancePara productPara = new MPInstancePara(instance, 50);
productPara.setParameter("M_Product_ID", product.getM_Product_ID());
productPara.saveEx();

processInfo.setAD_PInstance_ID(instance.getAD_PInstance_ID());

GenerateCostDetail process = new GenerateCostDetail();
process.startProcess(Env.getCtx(), processInfo, trx);
```

### Lesson

Some Adempiere processes require real process metadata through `AD_PInstance` and `AD_PInstance_Para`. Calling `startProcess()` without proper metadata can fail or silently skip required parameters.

---

## 10. Prepare Deterministic Test Data

### Ant Target

```xml
<target name="prepare-negative-inventory-test-data"
    description="reset costing state for Oak">
  <exec executable="psql" failonerror="true">
    <arg value="-v"/>
    <arg value="ON_ERROR_STOP=1"/>
    <arg value="-c"/>
    <arg value="
      set search_path to adempiere;

      delete from m_costdetail where m_product_id = 123;

      update m_cost
         set cumulatedqty = 0,
             cumulatedamt = 0,
             currentqty = 0,
             currentcostprice = 0
       where m_product_id = 123;
    "/>
  </exec>
</target>
```

### DBA Note

This is destructive for product `Oak` costing data. Use only in a disposable/local GardenWorld test database.

### Lesson

A repeatable test must control its initial state.

---

## 11. Verify Transaction History

### SQL

```sql
set search_path to adempiere;

select m_transaction_id,
       movementdate,
       movementtype,
       movementqty,
       sum(movementqty) over (
         order by movementdate, m_transaction_id
       ) as running_qty,
       m_inoutline_id,
       m_movementline_id,
       m_inventoryline_id
from m_transaction
where m_product_id = 123
order by movementdate, m_transaction_id;
```

### Observed Flow

```text
2021-07-25  C-  -1   running -1
2021-08-07  V+  10   running 9
2021-12-22  V+  15   running 24
2022-01-25  M-  -4   running 20
2022-01-25  M+   4   running 24
```

### Lesson

The negative inventory event exists only if the process date range includes:

```text
2021-07-25 -> 2022-01-25
```

A later range misses the negative event and produces misleading results.

---

## 12. Add Safe Instrumentation

### File

```text
base/src/org/adempiere/engine/AverageInvoiceCostingMethod.java
```

### Header Note

```java
/**
 * @author victor.perez@e-evolution.com, www.e-evolution.com
 * @author Systemhaus Westfalia SusanneCalderon <susanne.de.calderon@westfalia-it.com>
 * @author Horacio Miranda <hmiranda@prolinux.cl>, Prolinux (EFACT Ltda.)
 *    <li> Set M_MatchInv_ID and M_MatchPO_ID in Costdetail</li>
 *    https://github.com/adempiere/adempiere/issues/1918
 *
 *    <li> Add diagnostic debug instrumentation for costing analysis (Negative Inventory test)</li>
 *    Debug usage:
 *        Env.setContext(ctx, "#COST_DEBUG", "Y");
 *
 *    Notes:
 *        - Debug is OFF by default (controlled via Env context)
 *        - No functional behavior changes (instrumentation only)
 */
```

### Helper

```java
private void costDebug(String msg) {
  if ("Y".equals(Env.getContext(Env.getCtx(), "#COST_DEBUG"))) {
    System.out.println(msg);
  }
}
```

### Debug Calls

Inside the `+` movement branch:

```java
costDebug(">>> AIC + DEBUG quantityOnHand=" + quantityOnHand);
costDebug(">>> AIC + DEBUG movementQuantity=" + movementQuantity);
costDebug(">>> AIC + DEBUG resultQty=" + quantityOnHand.add(movementQuantity));
costDebug(">>> AIC + DEBUG costThisLevel=" + costThisLevel);
costDebug(">>> AIC + DEBUG previousCost="
    + getNewCurrentCostPrice(lastCostDetail, accountSchema.getCostingPrecision(), RoundingMode.HALF_UP));
```

### Lesson

Instrumentation must be:

- OFF by default
- enabled only by test context
- read-only
- free of behavior changes

---

## 13. Observe the Failure

### Final Test Output

```text
Expected Adjustment = 100
Actual Adjustment   = 0
FAIL: Cost adjustment mismatch: expected=100, actual=0
```

### Debug Signal

```text
quantityOnHand=-1
movementQuantity=10
resultQty=9
costThisLevel=0
previousCost=0
```

### Meaning

The engine sees the negative-to-positive inventory transition, but the cost basis is zero at that moment. Therefore no adjustment is generated.

This proves the failure without changing production behavior.

---

## Commit Strategy

### Commit 1: Failing Test and Instrumentation

```text
test(costing): reproduce missing negative inventory cost adjustment with instrumentation

- add deterministic negative inventory test scenario
- instrument AverageInvoiceCostingMethod with costDebug()
- observe negative→positive transition with zero costThisLevel
- demonstrate adjustment not generated (expected=100, actual=0)
- no functional behavior changes
```

### Commit 2: Logic Fix

Only after the failing test is committed:

```text
fix(costing): generate adjustment when negative inventory recovers with valid cost basis
```

### Commit 3: Documentation

```text
docs(testing): document negative inventory costing test workflow
```

---

## Developer Lessons

### Start Small

Begin with a Java class that only prints one line.

### Add One Layer at a Time

Recommended layer order:

```text
1. compile target
2. run target
3. Adempiere startup
4. context setup
5. transaction handling
6. model queries
7. process execution
8. deterministic data setup
9. assertion
10. instrumentation
```

### Keep Test and Fix Separate

First prove the bug. Then fix it.

### Prefer Business Assertions

A good test says:

```text
The business expected a cost adjustment, but the engine generated zero.
```

not only:

```text
method returned wrong number
```

### Instrument Carefully

Debugging production classes is acceptable only if:

- disabled by default
- enabled explicitly
- does not modify state
- is documented

---

## Current Checkpoint

Completed:

```text
Know Your Dragon
```

Current proven state:

```text
Negative inventory transition occurs
Cost engine sees recovery from negative stock
costThisLevel = 0
previousCost = 0
CostAdjustment remains 0
Test fails intentionally
```

Next phase:

```text
Slaying the Dragon
```

Goal:

```text
Implement the smallest safe logic change that makes the failing test pass.
```
