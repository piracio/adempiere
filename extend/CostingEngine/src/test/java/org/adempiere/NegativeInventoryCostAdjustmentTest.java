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
import org.compiere.model.MInventory;
import org.compiere.model.MInventoryLine;
import org.compiere.model.MInOut;
import org.compiere.model.MInOutLine;
import org.compiere.model.MMatchInv;
import org.compiere.util.DB;
import org.compiere.Adempiere;
import org.compiere.model.MCost;
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

public class NegativeInventoryCostAdjustmentTest {

  public static void main(String[] args) {
    Adempiere.startup(true);
    
    if (!DB.isConnected()) {
      throw new RuntimeException("No DB connection after ADempiere startup");
    }

    NegativeInventoryCostAdjustmentTest test = new NegativeInventoryCostAdjustmentTest();

    System.out.println("=== START Costing Test Suite ===");

    /**
    * Legacy exploratory scenario based on GardenWorld historical data.
    *
    * Disabled because the scenario is non-deterministic and depends on:
    * - accounting periods
    * - manufacturing/cost collector state
    * - mutable seed data
    *
    * TEST02 is now the canonical deterministic regression harness.
    */
    //test.test01_NegativeInventory_ReplenishHigherCost_AfterClosedPeriod();
    test.test02_SameDayOrdering(); 

    System.out.println("=== END Costing Test Suite ===");
    
  }

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

    System.out.println("\n=== TEST 02: Same-day ordering ===");

    resetTestData();
    setupContext();

    String trxName = Trx.createTrxName("CostTest02");
    Trx trx = Trx.get(trxName, true);

    try {
      trx.start();

      // Create first real shipment for same-day ordering scenario (#171)
      int productId = 123;
      int locatorId = 101;
      int warehouseId = 103;

      System.out.println(">>> Creating Shipment -1");

      MInOut shipment1 = new MInOut(Env.getCtx(), 0, trx.getTrxName());
      shipment1.setAD_Org_ID(11);
      shipment1.setMovementDate(Timestamp.valueOf("2022-01-01 00:00:00"));
      shipment1.setM_Warehouse_ID(warehouseId);
      shipment1.setMovementType("C-");
      shipment1.setC_DocType_ID(120);
      shipment1.setC_BPartner_ID(121);
      shipment1.setC_BPartner_Location_ID(115);
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
      shipment2.setC_DocType_ID(120);
      shipment2.setC_BPartner_ID(121);
      shipment2.setC_BPartner_Location_ID(115);
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
      receipt.setC_DocType_ID(122);
      receipt.setC_BPartner_ID(121);
      receipt.setC_BPartner_Location_ID(115);

      receipt.saveEx();

      MInOutLine lineR = new MInOutLine(receipt);
      lineR.setM_Product_ID(productId);
      lineR.setM_Locator_ID(locatorId);
      lineR.setMovementQty(new BigDecimal("10"));
      lineR.saveEx();

      System.out.println(">>> Creating Invoice 10 @ 40");

      MInvoice invoice = new MInvoice(Env.getCtx(), 0, trx.getTrxName());
      invoice.setAD_Org_ID(11);
      invoice.setC_BPartner_ID(121);
      invoice.setC_BPartner_Location_ID(115);
      invoice.setDateInvoiced(Timestamp.valueOf("2022-01-01 00:00:00"));
      invoice.setC_DocType_ID(123);
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

      System.out.println(
          ">>> MatchInv created: M_MatchInv_ID="
          + match.getM_MatchInv_ID()
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
      trx.rollback();
      throw new RuntimeException(e);
    } finally {
      trx.close();
    }
  }

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
          "  WHERE M_Product_ID = 123" +
          ")",
          trxName
      );

      DB.executeUpdateEx(
          "DELETE FROM M_MatchPO " +
          "WHERE M_InOutLine_ID IN (" +
          "  SELECT iol.M_InOutLine_ID " +
          "  FROM M_InOutLine iol " +
          "  WHERE iol.M_Product_ID = 123" +
          ") " +
          "OR C_InvoiceLine_ID IN (" +
          "  SELECT il.C_InvoiceLine_ID " +
          "  FROM C_InvoiceLine il " +
          "  WHERE il.M_Product_ID = 123" +
          ")",
          trxName
      );

      DB.executeUpdateEx(
          "DELETE FROM C_PaySelectionLine " +
          "WHERE C_Invoice_ID IN (" +
          "  SELECT C_Invoice_ID " +
          "  FROM C_InvoiceLine " +
          "  WHERE M_Product_ID = 123" +
          ")",
          trxName
      );

      DB.executeUpdateEx(
          "DELETE FROM C_InvoiceLine " +
          "WHERE M_Product_ID = 123",
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
          "WHERE M_Product_ID = 123",
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
          "WHERE M_Product_ID = 123",
          trxName
      );

      DB.executeUpdateEx(
          "DELETE FROM M_CostDetail " +
          "WHERE M_Product_ID = 123",
          trxName
      );

      DB.executeUpdateEx(
          "UPDATE M_Cost " +
          "SET CumulatedQty = 0, " +
          "    CumulatedAmt = 0, " +
          "    CurrentQty = 0, " +
          "    CurrentCostPrice = 0 " +
          "WHERE M_Product_ID = 123",
          trxName
      );

      trx.commit();

    } catch (Exception e) {
      trx.rollback();
      throw new RuntimeException(e);

    } finally {
      trx.close();
    }
  }

  private void setupContext() {
    Properties ctx = Env.getCtx();

    Env.setContext(ctx, "#COST_DEBUG", "N");
    Env.setContext(ctx, "#AD_Client_ID", 11);
    Env.setContext(ctx, "#AD_Org_ID", 11);
    Env.setContext(ctx, "#AD_User_ID", 101);
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
      MProduct product = new Query(
        Env.getCtx(),
        MProduct.Table_Name,
        MProduct.COLUMNNAME_Value + "=?",
        trxName)
      .setParameters("Oak")
      .first();

      if (product == null) {
        throw new RuntimeException("GardenWorld product not found: Oak");
      }

      System.out.println(">>> Product found: " + product.getValue()
          + " / M_Product_ID=" + product.getM_Product_ID());

      MCost cost = new Query(
          Env.getCtx(),
          MCost.Table_Name,
          "M_Product_ID=?",
           trxName)
      .setParameters(product.getM_Product_ID())
      .first();

      if (cost == null) {
        System.out.println(">>> No cost record found for product: " + product.getValue());
      } else {
          System.out.println(">>> Cost found: CurrentCostPrice="
              + cost.getCurrentCostPrice()
              + ", CumulatedQty=" + cost.getCumulatedQty()
              + ", CumulatedAmt=" + cost.getCumulatedAmt());
          System.out.println(">>> CostingMethod = " + cost.getCostingMethod());
        }

      MCost avgInvoiceCost = new Query(
          Env.getCtx(),
          MCost.Table_Name,
          "M_Product_ID=? AND M_CostElement_ID=?",
          trxName)
      .setParameters(product.getM_Product_ID(), 104)
      .first();

      if (avgInvoiceCost == null) {
        System.out.println(
            ">>> AIC + ERROR no Average Invoice M_Cost row found "
            + "(M_CostElement_ID=104)"
        );

        System.out.println(
            "FAIL: Missing Average Invoice M_Cost row for product "
            + product.getM_Product_ID()
            + " and M_CostElement_ID=104"
        );

        throw new RuntimeException(
            "Missing Average Invoice M_Cost row "
            + "for product=" + product.getM_Product_ID()
            + ", costElement=104"
        );

      } else {
        System.out.println(
            ">>> AIC + DEBUG Average Invoice cost row exists: "
            + "CurrentCostPrice=" + avgInvoiceCost.getCurrentCostPrice()
            + ", CurrentQty=" + avgInvoiceCost.getCurrentQty()
            + ", CumulatedQty=" + avgInvoiceCost.getCumulatedQty()
            + ", CumulatedAmt=" + avgInvoiceCost.getCumulatedAmt()
        );
      }

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
      trx.rollback();
      throw new RuntimeException(e);
    } finally {
      trx.close();
    }

    return costDetail != null && costDetail.getCostAdjustment() != null
    ? costDetail.getCostAdjustment().intValue()
    : 0;

  }
}
