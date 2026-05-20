 /********************************************************************
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
 **************************************************************************/
package org.adempiere;

import java.util.Properties;
import java.math.BigDecimal;
import org.compiere.model.MInOut;
import org.compiere.model.MInOutLine;
import org.compiere.model.MMatchInv;
import org.compiere.model.MCost;
import org.compiere.model.MCostDetail;
import org.compiere.model.MProduct;
import org.compiere.model.Query;
import org.compiere.util.Env;
import org.compiere.util.Trx;
import org.compiere.process.ProcessInfo;
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

  /***********************************************************************
   * REGRESSION FIXTURE CONSTANTS
   *********************************************************************/

  private static final int OAK_PRODUCT_ID = 123;
  private static final int AIC_COSTELEMENT_ID = 104;

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
     * TEST02 is now the canonical deterministic regression harness.
     */

    //test.test01_NegativeInventory_ReplenishHigherCost_AfterClosedPeriod();

    // Disabled until TEST03 AIC bootstrap invariant is resolved
    //test.test02_SameDayOrdering();
    test.test03_AverageInvoiceCostDimensionBootstrap();

    System.out.println("=== END Costing Test Suite ===");

  }

  /***********************************************************************