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

import org.compiere.util.DB;
import org.compiere.Adempiere;
import org.compiere.model.MCost;
import org.compiere.model.MCostDetail;
import org.compiere.model.MProduct;
import org.compiere.model.Query;
import org.compiere.util.Env;
import org.compiere.util.Trx;
import org.compiere.process.ProcessInfo;
import org.compiere.model.MPInstance;
import org.compiere.model.MPInstancePara;
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

    new NegativeInventoryCostAdjustmentTest()
        .test01_NegativeInventory_ReplenishHigherCost_AfterClosedPeriod();
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
    
    int actualAdjustment = calculateCurrentModelAdjustment();
    
    System.out.println("Expected Adjustment = " + expectedAdjustment);
    System.out.println("Actual Adjustment   = " + actualAdjustment);
    System.out.flush();
    System.err.flush();

    if (expectedAdjustment != actualAdjustment) {
      System.out.println("FAIL: Cost adjustment mismatch: expected="
          + expectedAdjustment + ", actual=" + actualAdjustment);
      System.out.flush();
      System.exit(1);
    }
  }

  private void setupContext() {
    Properties ctx = Env.getCtx();

    Env.setContext(ctx, "#COST_DEBUG", "Y");
    Env.setContext(ctx, "#AD_Client_ID", 11);
    Env.setContext(ctx, "#AD_Org_ID", 11);
    Env.setContext(ctx, "#AD_User_ID", 101);
    Env.setContext(ctx, "#M_Warehouse_ID", 103);

    System.out.println(">>> Context initialized for GardenWorld");
  }

  private int calculateCurrentModelAdjustment() {
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

      System.out.println(">>> Running GenerateCostDetail process AD_Process_ID=53223");

      ProcessInfo processInfo = new ProcessInfo("Generate Cost Transaction", 53223);
      processInfo.setAD_Client_ID(Env.getAD_Client_ID(Env.getCtx()));
      processInfo.setAD_Org_ID(Env.getAD_Org_ID(Env.getCtx()));
      processInfo.setAD_User_ID(Env.getAD_User_ID(Env.getCtx()));

      // Create process instance
      MPInstance instance = new MPInstance(Env.getCtx(), 53223, 0);
      instance.saveEx();

      // Set parameter: DateAcct range for existing GardenWorld transactions
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

    return costDetail != null
    ? costDetail.getCostAdjustment().intValue()
    : 0;

  }
}
