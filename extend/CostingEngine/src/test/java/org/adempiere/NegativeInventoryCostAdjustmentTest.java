/******************************************************************************
 * Product: ADempiere ERP & CRM Smart Business Solution                        *
 * Copyright (C) 2026 EFACT Ltda.                                              *
 * Contributor(s):                                                             *
 *   Prolinux <hmiranda@prolinux.cl>                                           *
 *                                                                             *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *                                                                             *
 * This program is distributed in the hope that it will be useful, but WITHOUT *
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or       *
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for    *
 * more details.                                                               *
 * Original File by: victor.perez@e-evolution.com, www.e-evolution.com         *
 *****************************************************************************/
package org.adempiere;

import java.util.Properties;
import java.math.BigDecimal;
import org.compiere.model.MInOut;
import org.compiere.model.MInOutLine;
import org.compiere.model.MMatchInv;
import org.compiere.util.DB;
import org.compiere.Adempiere;
import org.compiere.model.MCost;
import org.compiere.model.MAcctSchema;
import org.compiere.model.MCostElement;
import org.compiere.model.MCostType;
import org.compiere.model.MCostDetail;
import org.compiere.model.MProduct;
import org.compiere.model.Query;
import org.compiere.util.Env;
import org.compiere.util.Trx;
import org.compiere.process.ProcessInfo;
import org.compiere.process.DocAction;
import org.compiere.model.MPInstance;
import org.compiere.model.MPInstancePara;
import org.compiere.model.MInvoice;
import org.compiere.model.MInvoiceLine;
import java.sql.Timestamp;

import org.adempiere.test.CommonGWSetup;

import org.eevolution.manufacturing.process.GenerateCostDetail;

/**
 * Negative Inventory Cost Adjustment Test
 *
 * Business scenario:
 * - Sale generates negative inventory
 * - Accounting period is closed
 * - Replenishment occurs in a later period with different cost
 *
 * Objective:
 * - Validate that current costing model produces incorrect results
 * - Serve as a failing test for future cost adjustment implementation
 *
 * Expected behavior (future implementation):
 * - Closed period must remain unchanged
 * - Cost adjustment must be posted in open period
 * - Final average cost must reflect replenishment cost
 *
 * @author Prolinux (EFACT Ltda.)
 * @email hmiranda@prolinux.cl
 */

public class NegativeInventoryCostAdjustmentTest extends CommonGWSetup {

  /******************************************************************************
   * REGRESSION FIXTURE CONSTANTS
   *****************************************************************************/

  private static final int OAK_PRODUCT_ID = 123;
  private static final int GARDENWORLD_ACCTSCHEMA_ID = 101;

  private static final int SHIPMENT_CUSTOMER_DOCTYPE_ID = 120;
  private static final int RECEIPT_VENDOR_DOCTYPE_ID = 122;
  private static final int AP_INVOICE_DOCTYPE_ID = 123;

  private static final int GARDENWORLD_BP_ID = 121;
  private static final int GARDENWORLD_BP_LOCATION_ID = 115;

  @Override
  public void setUp() {
    super.setUp();

    Env.setContext(Env.getCtx(), "$C_Currency_ID", 100);

    System.out.println(">>> CommonGWSetup initialized");
  }

  public static void main(String[] args) {

    Adempiere.startup(true);

    if (!DB.isConnected()) {
      throw new RuntimeException("No DB connection after ADempiere startup");
    }

    NegativeInventoryCostAdjustmentTest test = new NegativeInventoryCostAdjustmentTest();

    System.out.println("=== START Costing Test Suite ===");

    /*
     * Legacy exploratory scenario based on GardenWorld historical data.
     *
     * Disabled because the scenario is non-deterministic and depends on:
     * - accounting periods
     * - manufacturing/cost collector state
     * - mutable seed data
     *
     * TEST02 contains the experimental same-day transaction scenario.
     * Only TEST03 bootstrap runs here; #4411 business assertions are still pending.
     */

    //test.test01_NegativeInventory_ReplenishHigherCost_AfterClosedPeriod();

    // Disabled until TEST03 AIC bootstrap invariant is resolved
    //test.test02_SameDayOrdering();
    test.test03_AverageInvoiceCostDimensionBootstrap();

    System.out.println("=== END Costing Test Suite ===");

  }

  /******************************************************************************
   * INTEGRATION REGRESSION TESTS
   *****************************************************************************/

  public void test01_NegativeInventory_ReplenishHigherCost_AfterClosedPeriod() {
    System.out.println("=== START Negative Inventory Cost Test ===");

    // Scenario:
    // Initial: 10 units @ 30
    // Sale:    20 units → -10 inventory
    // Replenish: 20 units @ 40

    int initialQty = 10;
    int initialCost = 30;

    int soldQty = 20;
    int replenishQty = 20;
    int replenishCost = 40;

    // Remaining negative qty = 10
    int negativeQty = soldQty - initialQty;

    // Expected adjustment:
    // 10 units were effectively valued at 30 but should be 40
    int expectedAdjustment = negativeQty * (replenishCost - initialCost);

    int actualAdjustment = runGenerateCostDetailForProductOak();

    System.out.println("Expected Adjustment = " + expectedAdjustment);
    System.out.println("Actual Adjustment   = " + actualAdjustment);
    System.out.flush();
    System.err.flush();

    if (expectedAdjustment != actualAdjustment) {
      System.out.println("FAIL: Cost adjustment mismatch: expected="
          + expectedAdjustment + ", actual=" + actualAdjustment);
    } else {
      System.out.println("PASS");
    }
  }

  public void test02_SameDayOrdering() {

    int productId = OAK_PRODUCT_ID;
    int locatorId = 101;
    int warehouseId = 103;

    System.out.println("\n=== TEST 02: Same-day ordering ===");

    resetTestData();
    setupContext();

    String trxName = Trx.createTrxName("CostTest02");
    Trx trx = Trx.get(trxName, true);

    try {
      trx.start();

      // Shipment creates negative inventory position

      System.out.println(">>> Creating Shipment -1");

      MInOut shipment1 = new MInOut(Env.getCtx(), 0, trx.getTrxName());
      shipment1.setAD_Org_ID(11);
      shipment1.setMovementDate(Timestamp.valueOf("2022-01-01 00:00:00"));
      shipment1.setM_Warehouse_ID(warehouseId);
      shipment1.setMovementType("C-");
      shipment1.setC_DocType_ID(SHIPMENT_CUSTOMER_DOCTYPE_ID);
      shipment1.setC_BPartner_ID(GARDENWORLD_BP_ID);
      shipment1.setC_BPartner_Location_ID(GARDENWORLD_BP_LOCATION_ID);
      shipment1.saveEx();

      MInOutLine line1 = new MInOutLine(shipment1);
      line1.setM_Product_ID(productId);
      line1.setM_Locator_ID(locatorId);
      line1.setMovementQty(new BigDecimal("1"));
      line1.saveEx();

      shipment1.processIt(DocAction.ACTION_Complete);
      shipment1.saveEx();

      System.out.println(">>> Creating Shipment -2");

      MInOut shipment2 = new MInOut(Env.getCtx(), 0, trx.getTrxName());
      shipment2.setAD_Org_ID(11);
      shipment2.setMovementDate(Timestamp.valueOf("2022-01-01 00:00:00"));
      shipment2.setM_Warehouse_ID(warehouseId);
      shipment2.setMovementType("C-");
      shipment2.setC_DocType_ID(SHIPMENT_CUSTOMER_DOCTYPE_ID);
      shipment2.setC_BPartner_ID(GARDENWORLD_BP_ID);
      shipment2.setC_BPartner_Location_ID(GARDENWORLD_BP_LOCATION_ID);
      shipment2.saveEx();

      MInOutLine line2 = new MInOutLine(shipment2);
      line2.setM_Product_ID(productId);
      line2.setM_Locator_ID(locatorId);
      line2.setMovementQty(new BigDecimal("2"));
      line2.saveEx();

      shipment2.processIt(DocAction.ACTION_Complete);
      shipment2.saveEx();

      System.out.println(">>> Creating Receipt +10");

      MInOut receipt = new MInOut(Env.getCtx(), 0, trx.getTrxName());
      receipt.setAD_Org_ID(11);
      receipt.setMovementDate(Timestamp.valueOf("2022-01-01 00:00:00"));
      receipt.setM_Warehouse_ID(warehouseId);
      receipt.setMovementType("V+");
      receipt.setC_DocType_ID(RECEIPT_VENDOR_DOCTYPE_ID);
      receipt.setC_BPartner_ID(GARDENWORLD_BP_ID);
      receipt.setC_BPartner_Location_ID(GARDENWORLD_BP_LOCATION_ID);

      receipt.saveEx();

      MInOutLine lineR = new MInOutLine(receipt);
      lineR.setM_Product_ID(productId);
      lineR.setM_Locator_ID(locatorId);
      lineR.setMovementQty(new BigDecimal("10"));
      lineR.saveEx();

      System.out.println(">>> Creating Invoice 10 @ 40");

      MInvoice invoice = new MInvoice(Env.getCtx(), 0, trx.getTrxName());
      invoice.setAD_Org_ID(11);
      invoice.setC_BPartner_ID(GARDENWORLD_BP_ID);
      invoice.setC_BPartner_Location_ID(GARDENWORLD_BP_LOCATION_ID);
      invoice.setDateInvoiced(Timestamp.valueOf("2022-01-01 00:00:00"));
      invoice.setC_DocType_ID(AP_INVOICE_DOCTYPE_ID);
      invoice.saveEx();

      MInvoiceLine invLine = new MInvoiceLine(invoice);
      invLine.setM_Product_ID(productId);
      invLine.setQty(new BigDecimal("10"));
      invLine.setPriceActual(new BigDecimal("40"));
      invLine.saveEx();

      invoice.processIt(DocAction.ACTION_Complete);
      invoice.saveEx();

      System.out.println(">>> Creating MatchInv");

      MMatchInv match = new MMatchInv(
          invLine,
          Timestamp.valueOf("2022-01-01 00:00:00"),
          new BigDecimal("10")
      );

      match.setM_InOutLine_ID(lineR.getM_InOutLine_ID());
      match.saveEx();

      System.out.println(
          ">>> MatchInv created: "
          + "MatchInv_ID=" + match.getM_MatchInv_ID()
          + ", InvoiceLine_ID=" + invLine.getC_InvoiceLine_ID()
          + ", InOutLine_ID=" + lineR.getM_InOutLine_ID()
      );

      receipt.processIt(DocAction.ACTION_Complete);
      receipt.saveEx();

      System.out.println(
          ">>> Receipt completed: M_InOut_ID="
          + receipt.getM_InOut_ID()
      );

      System.out.println(">>> Running GenerateCostDetail for synthetic scenario");

      int result = runGenerateCostDetailForProductOak();

      System.out.println(">>> Resulting adjustment (proxy): " + result);

      trx.commit();

    } catch (Exception e) {
      // Preserve deterministic database state for subsequent tests
      trx.rollback();
      throw new RuntimeException(e);

    } finally {
      trx.close();
    }
  }

  /**
   * Validate the Average Invoice / Material cost dimension bootstrap.
   * The costing method belongs to the schema's cost type, not element 104.
   * This is a fixture precondition check, not the closed-period regression.
   * All fixture writes are rolled back, including on success.
   */
  public void test03_AverageInvoiceCostDimensionBootstrap() {
    System.out.println("\n=== TEST 03: AIC cost dimension bootstrap ===");

    // main() invokes this directly, so JUnit's context callbacks do not run.
    Env.setContext(Env.getCtx(), "#AD_Client_ID", AD_CLIENT_ID);
    Env.setContext(Env.getCtx(), "#AD_Org_ID", AD_ORG_ID);
    Env.setContext(Env.getCtx(), "#AD_User_ID", AD_USER_ID);
    setupContext();

    String trxName = Trx.createTrxName("CostTest03");
    Trx trx = Trx.get(trxName, true);
    try {
      trx.start();
      MProduct product = getOakProduct(trxName);
      MAcctSchema schema = getAverageInvoiceSchema(product, trxName);
      MCostElement material = getDefaultMaterialElement(product, trxName);

      System.out.println(">>> Product found: " + product.getValue()
          + " / M_Product_ID=" + product.getM_Product_ID());
      System.out.println(">>> Schema=" + schema.getC_AcctSchema_ID()
          + ", CostType=" + schema.getM_CostType_ID()
          + ", MaterialElement=" + material.getM_CostElement_ID());

      // Exercise the model's bootstrap without deleting GardenWorld data.
      MCost.getOrCreate(product, 0, schema, 0, 0,
          schema.getM_CostType_ID(), material.getM_CostElement_ID());
      assertAverageInvoiceCostExists(product, trxName);
    } finally {
      try {
        if (!trx.rollback()) {
          throw new IllegalStateException("TEST03 fixture rollback failed");
        }
      } finally {
        trx.close();
      }
    }
    System.out.println(">>> TEST03 fixture rolled back; no cost setup committed");
  }

  /******************************************************************************
   * TEST INFRASTRUCTURE / HELPERS
   *****************************************************************************/

  /**
   * Reset transactional and costing state for deterministic
   * negative inventory regression execution.
   *
   * Removes:
   * - M_MatchInv
   * - M_MatchPO
   * - invoices
   * - shipments
   * - transactions
   * - cost detail records
   *
   * Preserves:
   * - master data
   * - product configuration
   * - costing schema
   */

  private void resetTestData() {

    String trxName = Trx.createTrxName("ResetCostTest");
    Trx trx = Trx.get(trxName, true);

    try {
      trx.start();

      // Match / invoice layer
      DB.executeUpdateEx(
          "DELETE FROM M_MatchInv " +
          "WHERE M_InOutLine_ID IN (" +
          "  SELECT M_InOutLine_ID " +
          "  FROM M_InOutLine " +
          "  WHERE M_Product_ID = " +
          OAK_PRODUCT_ID +
          ")",
          trxName
      );

      DB.executeUpdateEx(
          "DELETE FROM M_MatchPO " +
          "WHERE M_InOutLine_ID IN (" +
          "  SELECT iol.M_InOutLine_ID " +
          "  FROM M_InOutLine iol " +
          "  WHERE iol.M_Product_ID = " +
          OAK_PRODUCT_ID +
          ") " +
          "OR C_InvoiceLine_ID IN (" +
          "  SELECT il.C_InvoiceLine_ID " +
          "  FROM C_InvoiceLine il " +
          "  WHERE il.M_Product_ID = " +
          OAK_PRODUCT_ID +
          ")",
          trxName
      );

      DB.executeUpdateEx(
          "DELETE FROM C_PaySelectionLine " +
          "WHERE C_Invoice_ID IN (" +
          "  SELECT C_Invoice_ID " +
          "  FROM C_InvoiceLine " +
          "  WHERE M_Product_ID = " +
          OAK_PRODUCT_ID +
          ")",
          trxName
      );

      DB.executeUpdateEx(
          "DELETE FROM C_InvoiceLine " +
          "WHERE M_Product_ID = " +
          OAK_PRODUCT_ID,
          trxName
      );

      DB.executeUpdateEx(
          "DELETE FROM C_Invoice " +
          "WHERE C_Invoice_ID NOT IN (" +
          "  SELECT DISTINCT C_Invoice_ID " +
          "  FROM C_InvoiceLine " +
          ")",
          trxName
      );

      // Receipt / shipment layer
      DB.executeUpdateEx(
          "DELETE FROM M_InOutLine " +
          "WHERE M_Product_ID = " +
          OAK_PRODUCT_ID,
          trxName
      );

      DB.executeUpdateEx(
          "DELETE FROM M_InOut " +
          "WHERE M_InOut_ID NOT IN (" +
          "  SELECT DISTINCT M_InOut_ID " +
          "  FROM M_InOutLine" +
          ")",
          trxName
      );

      // Transaction + costing
      DB.executeUpdateEx(
          "DELETE FROM M_Transaction " +
          "WHERE M_Product_ID = " +
          OAK_PRODUCT_ID,
          trxName
      );

      DB.executeUpdateEx(
          "DELETE FROM M_CostDetail " +
          "WHERE M_Product_ID = " +
          OAK_PRODUCT_ID,
          trxName
      );

      DB.executeUpdateEx(
          "UPDATE M_Cost " +
          "SET CumulatedQty = 0, " +
          "    CumulatedAmt = 0, " +
          "    CurrentQty = 0, " +
          "    CurrentCostPrice = 0 " +
          "WHERE M_Product_ID = " +
          OAK_PRODUCT_ID,
          trxName
      );

      trx.commit();

    } catch (Exception e) {
      // Preserve deterministic database state for subsequent tests
      trx.rollback();
      throw new RuntimeException(e);

    } finally {
      trx.close();
    }
  }

  private void setupContext() {
    Properties ctx = Env.getCtx();

    Env.setContext(ctx, "#COST_DEBUG", "N");
    Env.setContext(ctx, "#M_Warehouse_ID", 103);

    System.out.println(">>> Context initialized for GardenWorld");
  }

  private int runGenerateCostDetailForProductOak() {
    System.out.println(">>> Probing current cost model (Env + Trx)");

    setupContext();

    MCostDetail costDetail = null;
    String trxName = Trx.createTrxName("CostTest");
    Trx trx = Trx.get(trxName, true);

    try {
      trx.start();

      System.out.println(">>> Trx started: " + trxName);
      System.out.println(">>> AD_Client_ID = " + Env.getAD_Client_ID(Env.getCtx()));
      MProduct product = getOakProduct(trxName);

      System.out.println(">>> Product found: " + product.getValue()
          + " / M_Product_ID=" + product.getM_Product_ID());

      MCost cost = getAverageInvoiceCost(product, trxName);

      if (cost == null) {
        System.out.println(">>> No cost record found for product: " + product.getValue());
      } else {
        System.out.println(">>> Cost found: CurrentCostPrice="
            + cost.getCurrentCostPrice()
            + ", CumulatedQty=" + cost.getCumulatedQty()
            + ", CumulatedAmt=" + cost.getCumulatedAmt());
      }

      assertAverageInvoiceCostExists(product, trxName);

      System.out.println(">>> Running GenerateCostDetail process AD_Process_ID=53223");

      ProcessInfo processInfo = new ProcessInfo("Generate Cost Transaction", 53223);
      processInfo.setAD_Client_ID(Env.getAD_Client_ID(Env.getCtx()));
      processInfo.setAD_Org_ID(Env.getAD_Org_ID(Env.getCtx()));
      processInfo.setAD_User_ID(Env.getAD_User_ID(Env.getCtx()));

      // Create process instance
      MPInstance instance = new MPInstance(Env.getCtx(), 53223, 0);
      instance.saveEx();

      // Set parameter: DateAcct range for existing GardenWorld transactions
      MPInstancePara paraFrom = new MPInstancePara(instance, 10);
      paraFrom.setParameter(
          "DateAcct",
          Timestamp.valueOf("2021-07-25 00:00:00")
      );
      paraFrom.saveEx();

      // DateAcct TO parameter
      MPInstancePara paraTo = new MPInstancePara(instance, 20);
      paraTo.setParameter(
          "DateAcct_To",
          Timestamp.valueOf("2022-01-25 00:00:00")
      );
      paraTo.saveEx();

      MPInstancePara productPara = new MPInstancePara(instance, 50);
      productPara.setParameter("M_Product_ID", product.getM_Product_ID());
      productPara.saveEx();

      processInfo.setAD_PInstance_ID(instance.getAD_PInstance_ID());

      GenerateCostDetail process = new GenerateCostDetail();

      try {
        process.startProcess(Env.getCtx(), processInfo, trx);
      } catch (Exception e) {
        System.out.println(">>> GenerateCostDetail warning: " + e.getMessage());
      }

      System.out.println(">>> GenerateCostDetail finished");

      costDetail = new Query(
          Env.getCtx(),
          MCostDetail.Table_Name,
          "M_Product_ID=?",
          trxName)
      .setParameters(product.getM_Product_ID())
      .setOrderBy("DateAcct DESC, M_CostDetail_ID DESC")
      .first();

      if (costDetail == null) {
        System.out.println(">>> No cost detail found for product: " + product.getValue());
      } else {
        System.out.println(">>> Last CostDetail: DateAcct=" + costDetail.getDateAcct()
            + ", Amt=" + costDetail.getAmt()
            + ", CostAdjustment=" + costDetail.getCostAdjustment()
            + ", Qty=" + costDetail.getQty()
            + ", CurrentCostPrice=" + costDetail.getCurrentCostPrice());
      }

      trx.commit();
    } catch (Exception e) {
      // Preserve deterministic database state for subsequent tests
      trx.rollback();
      throw new RuntimeException(e);

    } finally {
      trx.close();
    }

    return costDetail != null
        && costDetail.getCostAdjustment() != null
        ? costDetail.getCostAdjustment().intValue()
        : 0;
  }

  private MProduct getOakProduct(String trxName) {

    MProduct product = new Query(
        Env.getCtx(),
        MProduct.Table_Name,
        "M_Product_ID=?",
        trxName)
    .setParameters(OAK_PRODUCT_ID)
    .first();

    if (product == null) {
      throw new RuntimeException("Product Oak not found");
    }

    return product;
  }

  private MAcctSchema getAverageInvoiceSchema(MProduct product, String trxName) {
    MAcctSchema schema = new MAcctSchema(product.getCtx(),
        GARDENWORLD_ACCTSCHEMA_ID, trxName);
    if (schema.get_ID() != GARDENWORLD_ACCTSCHEMA_ID
        || !schema.isActive()
        || schema.getAD_Client_ID() != product.getAD_Client_ID()) {
      throw new IllegalStateException("GardenWorld accounting schema is missing or inactive");
    }
    MCostType costType = new MCostType(product.getCtx(),
        schema.getM_CostType_ID(), trxName);
    if (costType.get_ID() == 0 || !costType.isActive()
        || costType.getAD_Client_ID() != product.getAD_Client_ID()
        || !MCostType.COSTINGMETHOD_AverageInvoice.equals(costType.getCostingMethod())) {
      throw new IllegalStateException("Schema must select an active Average Invoice cost type");
    }
    // This GardenWorld fixture uses client-level costing with zero org/warehouse/ASI.
    if (!MAcctSchema.COSTINGLEVEL_Client.equals(product.getCostingLevel(schema))) {
      throw new IllegalStateException("TEST03 requires client-level costing");
    }
    return schema;
  }

  private MCostElement getDefaultMaterialElement(MProduct product, String trxName) {
    MCostElement material = new Query(product.getCtx(), MCostElement.Table_Name,
        "AD_Client_ID=? AND CostElementType=? AND IsDefault='Y'", trxName)
        .setParameters(product.getAD_Client_ID(), MCostElement.COSTELEMENTTYPE_Material)
        .setOnlyActiveRecords(true)
        .firstOnly();
    if (material == null) {
      throw new IllegalStateException("No active default Material cost element for GardenWorld");
    }
    return material;
  }

  private MCost getAverageInvoiceCost(MProduct product, String trxName) {
    MAcctSchema schema = getAverageInvoiceSchema(product, trxName);
    MCostElement material = getDefaultMaterialElement(product, trxName);
    return new Query(product.getCtx(), MCost.Table_Name,
        "AD_Client_ID=? AND M_Product_ID=? AND C_AcctSchema_ID=?"
        + " AND M_CostType_ID=? AND M_CostElement_ID=?"
        + " AND AD_Org_ID=0 AND M_Warehouse_ID=0 AND M_AttributeSetInstance_ID=0", trxName)
        .setParameters(product.getAD_Client_ID(), product.getM_Product_ID(),
            schema.getC_AcctSchema_ID(), schema.getM_CostType_ID(),
            material.getM_CostElement_ID())
        .setOnlyActiveRecords(true)
        .firstOnly();
  }

  private void assertAverageInvoiceCostExists(
      MProduct product,
      String trxName) {

    MCost aicCost = getAverageInvoiceCost(product, trxName);

    if (aicCost == null) {

      System.out.println(
          "FAIL: Missing Material M_Cost row for the schema's Average Invoice cost type"
      );

      System.out.flush();
      System.err.flush();

      throw new RuntimeException(
          "AIC bootstrap failure for product="
          + product.getM_Product_ID()
      );
    }

    System.out.println(
        "PASS: Material M_Cost row for Average Invoice cost type exists"
    );

    System.out.println(
        ">>> CurrentCostPrice="
        + aicCost.getCurrentCostPrice()
        + ", CurrentQty="
        + aicCost.getCurrentQty()
        + ", CumulatedQty="
        + aicCost.getCumulatedQty()
    );
  }

  private void dumpCostState(
      int productId,
      String trxName) {

    System.out.println(">>> COST STATE DUMP");

    java.util.List<MCost> costs = new Query(
        Env.getCtx(),
        MCost.Table_Name,
        "M_Product_ID=?",
        trxName)
    .setParameters(productId)
    .list();

    for (MCost cost : costs) {

      System.out.println(
          ">>> M_Cost:"
          + " CostElement=" + cost.getM_CostElement_ID()
          + " CurrentCostPrice=" + cost.getCurrentCostPrice()
          + " CurrentQty=" + cost.getCurrentQty()
          + " CumulatedQty=" + cost.getCumulatedQty()
          + " CumulatedAmt=" + cost.getCumulatedAmt());
    }
  }

  /******************************************************************************
   * UNIT VALIDATION TESTS
   *
   * Future isolated arithmetic validation layer for:
   * - Average Invoice calculations
   * - negative inventory math
   * - rounding
   * - adjustment deltas
   * - zero/NULL quantity handling
   *****************************************************************************/

}
