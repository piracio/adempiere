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

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.net.URI;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.eevolution.manufacturing.process.GenerateCostDetail;
import org.adempiere.exceptions.ZeroSalesCostException;
import org.adempiere.engine.CostEngine;
import org.adempiere.engine.CostEngineFactory;
import org.adempiere.engine.AverageInvoiceCostingMethod;
import org.adempiere.test.CommonGWData;
import org.adempiere.test.IntegrationTestTag;
import org.compiere.Adempiere;
import org.compiere.acct.Doc;
import org.compiere.model.*;
import org.compiere.process.DocAction;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Trx;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Issue #4411: negative inventory costing and accounting regressions.
 *
 * Requires an explicitly allowlisted disposable GardenWorld database identity. Sequence
 * allocation can commit independently even when every business row rolls back.
 * Use an idle disposable clone: fixtures temporarily close periods and restore them.
 */
@Execution(ExecutionMode.SAME_THREAD)
public class NegativeInventoryCostAdjustmentTest implements IntegrationTestTag {
    private static final String TEST_YEAR = System.getProperty("adempiere.costtest.database.accountingYear", "2022");
    private static final String DB_IDENTITY_PREFIX = "adempiere.costtest.database.";
    private static final int CLIENT_ID = CommonGWData.AD_CLIENT_ID;
    private static final int ORG_ID = CommonGWData.AD_ORG_ID;
    private static final int SCHEMA_ID = 101;
    private static final int TEMPLATE_PRODUCT_ID = 123; // read-only configuration source
    private static final int WAREHOUSE_ID = CommonGWData.HQ_WAREHOUSE_ID;
    private static final int LOCATOR_ID = 101;
    private static final String COST_KEY = "AD_Client_ID=? AND M_Product_ID=?"
            + " AND C_AcctSchema_ID=? AND M_CostType_ID=? AND M_CostElement_ID=?";
    private static final String DIMENSION = COST_KEY
            + " AND AD_Org_ID=0 AND M_Warehouse_ID=0 AND M_AttributeSetInstance_ID=0";

    @BeforeAll
    public static void initialize() {
        if (!TEST_YEAR.matches("[12][0-9]{3}")) throw new IllegalArgumentException("Invalid test accounting year");
        DatabaseIdentity identity = DatabaseIdentity.configured(); // fail before startup
        Adempiere.startup(true);
        // startup(true) loads connection settings; verify a real connection before any fixture.
        try (Connection connection = DB.getConnectionRW()) {
            identity.verify(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("Database identity verification failed", e);
        }
    }

    /** One exact endpoint, database and login; no wildcard, arbitrary URL options or failover hosts. */
    static final class DatabaseIdentity {
        final String url = required("jdbcUrl");
        final String address = required("serverAddress");
        final String port = required("serverPort");
        final String database = required("databaseName");
        final String user = required("user");

        static DatabaseIdentity configured() {
            DatabaseIdentity identity = new DatabaseIdentity();
            try {
                if (!identity.url.startsWith("jdbc:postgresql://")) {
                    throw new IllegalArgumentException("Only explicit PostgreSQL endpoints are supported");
                }
                URI endpoint = new URI(identity.url.substring(5));
                if (endpoint.getHost() == null || endpoint.getPort() < 1
                        || endpoint.getPort() > 65535 || endpoint.getUserInfo() != null
                        || (endpoint.getRawQuery() != null
                            && !"encoding=UNICODE".equals(endpoint.getRawQuery()))
                        || endpoint.getFragment() != null
                        || !endpoint.getPath().equals("/" + identity.database)
                        || !identity.port.equals(Integer.toString(endpoint.getPort()))
                        || !identity.database.matches("[A-Za-z0-9_\\-]+")) {
                    throw new IllegalArgumentException("Ambiguous database identity configuration");
                }
                // Require a literal server address; never silently resolve an allowlist hostname.
                literalAddress(identity.address);
            } catch (Exception e) {
                throw new IllegalStateException("Invalid test database allowlist", e);
            }
            return identity;
        }

        private static byte[] literalAddress(String value) {
            if (value != null && value.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) {
                String[] octets = value.split("\\.");
                byte[] bytes = new byte[4];
                for (int i = 0; i < bytes.length; i++) {
                    int octet = Integer.parseInt(octets[i]);
                    if (octet > 255) {
                        throw new IllegalArgumentException("IPv4 octet out of range");
                    }
                    bytes[i] = (byte) octet;
                }
                return bytes;
            }
            if (value != null && value.matches("[0-9a-fA-F:]+") && value.contains(":")) {
                try {
                    // A colon-containing numeric literal cannot trigger hostname resolution.
                    return InetAddress.getByName(value).getAddress();
                } catch (UnknownHostException e) {
                    throw new IllegalArgumentException("Invalid IPv6 literal", e);
                }
            }
            throw new IllegalArgumentException("serverAddress must be a literal IP address");
        }

        private boolean matchesAddress(String actual) {
            try {
                // PostgreSQL's inet::text retains /32 (IPv4) or /128 (IPv6).
                // Accept only a complete host mask, never a subnet allowlist.
                if (actual != null && actual.contains("/")) {
                    int slash = actual.indexOf('/');
                    String hostMask = actual.contains(":") ? "/128" : "/32";
                    if (!hostMask.equals(actual.substring(slash))) {
                        return false;
                    }
                    actual = actual.substring(0, slash);
                }
                // Keep IPv4 and IPv6 distinct, including IPv4-mapped IPv6 literals.
                return actual != null && address.contains(":") == actual.contains(":")
                        && Arrays.equals(literalAddress(address), literalAddress(actual));
            } catch (IllegalArgumentException e) {
                return false; // Missing or malformed server evidence must fail closed.
            }
        }

        static String required(String key) {
            String value = System.getProperty(DB_IDENTITY_PREFIX + key);
            if (value == null || value.isEmpty() || !value.equals(value.trim())
                    || value.contains("${") || value.contains("*") || value.contains(",")) {
                throw new IllegalStateException("Missing or ambiguous test database identity: " + key);
            }
            return value;
        }

        void verify(Connection connection) throws SQLException {
            if (connection == null || connection.isClosed()
                    || !url.equals(connection.getMetaData().getURL())) {
                throw new IllegalStateException("Actual JDBC target is not the allowlisted test endpoint");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT inet_server_addr()::text, inet_server_port()::text,"
                    + " current_database(), session_user, current_user");
                    ResultSet row = statement.executeQuery()) {
                if (!row.next() || !matchesAddress(row.getString(1))
                        || !port.equals(row.getString(2)) || !database.equals(row.getString(3))
                        || !user.equals(row.getString(4)) || !user.equals(row.getString(5))
                        || row.next()) {
                    throw new IllegalStateException("Actual server/database/login is not allowlisted");
                }
            }
        }
    }

    /** Standalone entry point uses the same safeguards and assertions as JUnit. */
    public static void main(String[] args) throws Exception {
        String test = args.length == 0 ? "TEST03" : args[0];
        if (args.length > 1 || !("TEST01".equals(test) || "TEST02".equals(test) || "TEST03".equals(test) || "TEST04".equals(test)
                || "TEST05".equals(test) || "TEST06".equals(test) || "TEST07".equals(test)
                || "TEST08".equals(test) || "TEST09".equals(test) || "TEST10".equals(test)
                || "TEST11".equals(test) || "TEST12".equals(test) || "TEST13".equals(test)
                || "TEST14".equals(test) || "TEST15".equals(test) || "TEST16".equals(test)
                || "TEST17".equals(test) || "TEST18".equals(test) || "TEST19".equals(test) || "TEST20".equals(test) || "TEST21".equals(test) || "TEST22".equals(test) || "TEST23".equals(test)
                || "TEST24".equals(test) || "TEST25".equals(test) || "TEST26".equals(test) || "TEST27".equals(test) || "TEST28".equals(test) || "TEST29".equals(test) || "TEST30".equals(test)
                || "TEST31".equals(test) || "TEST32".equals(test) || "TEST33".equals(test) || "TEST34".equals(test) || "TEST35".equals(test))) {
            throw new IllegalArgumentException("Select TEST01 through TEST35");
        }
        initialize();
        NegativeInventoryCostAdjustmentTest suite = new NegativeInventoryCostAdjustmentTest();
        if ("TEST01".equals(test)) {
            suite.test01_NegativeInventory_ReplenishHigherCost_AfterClosedPeriod();
        } else if ("TEST02".equals(test)) {
            suite.test02_SameDayOrdering();
        } else if ("TEST03".equals(test)) {
            suite.test03_AverageInvoiceCostDimensionBootstrap();
        } else if ("TEST04".equals(test)) {
            suite.test04_ReplenishmentWithDifferentCost();
        } else if ("TEST05".equals(test)) {
            suite.test05_ReplenishmentVariants("5", "40", "-5", "-150", "30");
            suite.test05_ReplenishmentVariants("10", "40", "0", "0", "30");
            suite.test05_ReplenishmentVariants("20", "20", "10", "200", "20");
            suite.test05_ReplenishmentVariants("20", "30", "10", "300", "30");
        } else if ("TEST06".equals(test)) {
            suite.test06_ReprocessingReceiptIsIdempotent();
        } else if ("TEST07".equals(test)) {
            suite.test07_AccountingRecognizesCoveredCostDifference();
        } else if ("TEST08".equals(test)) {
            suite.test08_MultipleReceiptsSettleOnlyRemainingNegativeQuantity();
        } else if ("TEST09".equals(test)) {
            suite.test09_ReversingReplenishmentRestoresProvisionalValue();
        } else if ("TEST10".equals(test)) {
            suite.test10_InvoicePriceDifferenceIsSplitBetweenStockAndCoveredIssues("20", "50", "500", "50", "200");
        } else if ("TEST11".equals(test)) {
            suite.test11_InvoiceReversalRestoresReceiptValuation();
        } else if ("TEST12".equals(test)) {
            suite.test12_ClosedMatchPreventsPartialAccountingReset();
        } else if ("TEST13".equals(test)) {
            suite.test13_LateInvoiceAfterReceiptPeriodClosed();
        } else if ("TEST14".equals(test)) {
            suite.test14_ReplayCannotRenumberClosedSuccessors();
        } else if ("TEST15".equals(test)) {
            suite.test15_MatchPOReversalUsesItsOwnOriginalMatch();
        } else if ("TEST16".equals(test)) {
            suite.test16_BulkRejectsClosedHistoryBeforeDeletingAnything();
        } else if ("TEST17".equals(test)) {
            suite.test17_BulkIncludesFirstAndLastDayInvoiceMatches();
        } else if ("TEST18".equals(test)) {
            suite.test18_BulkFailureOnSecondProductRestoresWholeBatch();
        } else if ("TEST19".equals(test)) {
            suite.test19_BulkRejectsClosedRelatedInvoiceMatch();
        } else if ("TEST20".equals(test)) {
            suite.test20_BulkRejectsAnIncompleteCostingTail();
        } else if ("TEST21".equals(test)) {
            suite.test21_QuantityAggregationDoesNotMixAccountingSchemas();
        } else if ("TEST22".equals(test)) {
            suite.test22_SalesWithZeroCostAreRejected("0");
            suite.test22_SalesWithZeroCostAreRejected("10");
        } else if ("TEST23".equals(test)) {
            suite.test23_NegativeStockWithKnownProvisionalCostRemainsAllowed();
        } else if ("TEST24".equals(test)) {
            suite.test24_PostingRejectsLegacyZeroOrMissingCosts("ZERO");
            suite.test24_PostingRejectsLegacyZeroOrMissingCosts("MISSING");
        } else if ("TEST25".equals(test)) {
            suite.test25_ZeroOptionalComponentsAndLowerLevelCostAreHandledTogether();
        } else if ("TEST26".equals(test)) {
            suite.test26_ZeroCostSecondLineRejectsWholeShipment();
        } else if ("TEST27".equals(test)) {
            suite.test27_DeferredCostingCannotPostOrRegenerateAZeroCostSale();
        } else if ("TEST28".equals(test)) {
            suite.test28_ParallelCostRegenerationDoesNotValidateAnUnselectedAccountingType();
        } else if ("TEST29".equals(test)) {
            suite.test29_LateLandedCostIsSelectedWithoutRebuildingClosedReceipt("0", "20", "800", "40", "0");
            suite.test29_LateLandedCostIsSelectedWithoutRebuildingClosedReceipt("10", "10", "400", "40", "100");
            suite.test29_LateLandedCostIsSelectedWithoutRebuildingClosedReceipt("20", "0", "0", "30", "200");
        } else if ("TEST30".equals(test)) {
            suite.test30_LateLandedCostCreditHasTheOppositeAccountingSign();
        } else if ("TEST31".equals(test)) {
            suite.test31_TwoLateCostsAndDirectReplayKeepTheClosedReceiptUnchanged();
        } else if ("TEST32".equals(test)) {
            suite.test32_ClosedLateInvoicePreventsAnyCostOrAccountingChanges();
        } else if ("TEST33".equals(test)) {
            suite.test33_ReplayCannotTouchALandedCostInAClosedSuccessorPeriod();
        } else if ("TEST34".equals(test)) {
            suite.test34_FullySoldLandedCostKeepsAKnownProvisionalCostForTheNextSale();
        } else {
            suite.test35_LateCostSurvivesRebuildingPhysicalMovementsInTheOpenPeriod();
        }
    }

    @Test
    public void test01_NegativeInventory_ReplenishHigherCost_AfterClosedPeriod() {
        try (Fixture f = new Fixture("TEST01")) {
            Timestamp january = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            Timestamp february = Timestamp.valueOf(TEST_YEAR + "-02-01 00:00:00");
            f.assertDocumentPreconditions(january);
            f.assertDocumentPreconditions(february);
            f.createProduct();
            f.useExistingPartner();
            MInOutLine initial = f.movement(false, "10", "30", january);
            f.post(MInOut.Table_ID, initial.getM_InOut_ID());
            MInOutLine sale = f.movement(true, "20", "30", Timestamp.valueOf(TEST_YEAR + "-01-02 00:00:00"));
            f.post(MInOut.Table_ID, sale.getM_InOut_ID());
            f.assertCostState("-10", "-300", "30");
            // Use an older audit marker on owned rows: timestamps have second
            // precision, so a fast replay must not hide writes in the same second.
            DB.executeUpdateEx("UPDATE M_CostDetail SET Updated=? WHERE M_Product_ID=?",
                    new Object[] {Timestamp.valueOf("2000-01-01 00:00:00"), f.productId}, f.name);
            List<List<String>> facts = f.historicalRows("Fact_Acct", february);
            List<List<String>> details = f.historicalRows("M_CostDetail", february);
            f.closePeriod(january);
            assertFalse(MPeriod.isOpen(f.ctx, january, MDocType.DOCBASETYPE_MaterialDelivery, ORG_ID, f.name));
            MInOutLine receipt = f.movement(false, "20", "40", february);
            f.post(MInOut.Table_ID, receipt.getM_InOut_ID());
            MInvoiceLine invoice = f.invoice(receipt, "20", "40", february);
            f.post(MInvoice.Table_ID, invoice.getC_Invoice_ID());
            MMatchInv match = new Query(f.ctx, MMatchInv.Table_Name,
                    "C_InvoiceLine_ID=? AND M_InOutLine_ID=?", f.name)
                    .setParameters(invoice.get_ID(), receipt.get_ID()).firstOnly();
            f.post(MMatchInv.Table_ID, match.get_ID());
            f.assertCostState("10", "400", "40");
            assertDecimal("400", f.accountBalance("P_Asset_Acct"), "Inventory after closed-period replenishment");
            assertDecimal("100", f.accountBalance("P_CostAdjustment_Acct"), "Open-period cost difference");
            List<MCostDetail> closed = new Query(f.ctx, MCostDetail.Table_Name,
                    COST_KEY + " AND M_InOutLine_ID=? AND Qty<0", f.name)
                    .setParameters(CLIENT_ID, f.productId, SCHEMA_ID, f.schema.getM_CostType_ID(),
                            f.material.get_ID(), sale.get_ID()).list();
            assertFalse(closed.isEmpty());
            for (MCostDetail detail : closed)
                new AverageInvoiceCostingMethod().adjustCostDetail(detail);
            MTransaction closedTransaction = new MTransaction(f.ctx, closed.get(0).getM_Transaction_ID(), f.name);
            CostEngineFactory.getCostEngine(CLIENT_ID).createCostDetail(f.schema,
                    (MCostType) f.schema.getM_CostType(), f.material, closedTransaction, sale, true);
            f.assertCostState("10", "400", "40");
            assertEquals(facts, f.historicalRows("Fact_Acct", february), "Closed-period facts must remain unchanged");
            assertEquals(details, f.historicalRows("M_CostDetail", february), "Closed-period costs must remain unchanged");
        }
    }

    /** Same accounting day, explicit insertion/completion order, no historical replay. */
    @Test
    public void test02_SameDayOrdering() {
        DatabaseIdentity.configured();
        try (Fixture f = new Fixture("TEST02")) {
            Timestamp day = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            f.assertDocumentPreconditions(day);
            f.createProduct();
            f.createPartner();
            // Establish an actual known provisional cost, then exhaust stock.
            f.movement(false, "1", "40", day);
            f.movement(true, "1", "40", day);
            f.assertCostState("0", "0", "40");
            int openingMovements = f.count("M_Transaction");
            MInOutLine sale1 = f.movement(true, "1", day);
            MInOutLine sale2 = f.movement(true, "2", day);
            MInOutLine receipt = f.movement(false, "10", day);
            MInvoiceLine invoice = f.invoice(receipt, day);

            List<MMatchInv> matches = new Query(f.ctx, MMatchInv.Table_Name,
                    "C_InvoiceLine_ID=? AND M_InOutLine_ID=?", f.name)
                    .setParameters(invoice.get_ID(), receipt.get_ID()).list();
            // Invoice completion creates the match through its linked receipt line.
            assertEquals(1, matches.size(), "Expected exactly one receipt/invoice match");
            MMatchInv match = matches.get(0);
            f.assertOwned(match);
            assertEquals(f.productId, match.getM_Product_ID());
            assertEquals(day, match.getDateAcct());
            assertEquals(day, match.getDateTrx());
            assertDecimal("10", match.getQty(), "Matched quantity");

            int first = assertMovement(f, sale1, "C-", "-1", day);
            int second = assertMovement(f, sale2, "C-", "-2", day);
            int third = assertMovement(f, receipt, "V+", "10", day);
            assertTrue(first < second && second < third, "Same-day transaction insertion order");
            assertEquals(openingMovements + 3, f.count("M_Transaction"), "No extra inventory transactions");
            assertDecimal("7", DB.getSQLValueBDEx(f.name,
                    "SELECT SUM(QtyOnHand) FROM M_Storage WHERE M_Product_ID=?", f.productId),
                    "Final stock after two shipments and replenishment");
            f.assertCostState("7", "280", "40");
            BigDecimal firstProcessed = sale1.getParent().getProcessedOn();
            BigDecimal secondProcessed = sale2.getParent().getProcessedOn();
            BigDecimal receiptProcessed = receipt.getParent().getProcessedOn();
            assertTrue(firstProcessed.signum() > 0, "Shipments must retain their processing timestamp");
            assertTrue(firstProcessed.compareTo(secondProcessed) <= 0, "Shipment processing order");
            assertTrue(secondProcessed.compareTo(receiptProcessed) <= 0, "Receipt processing order");
            // The ordering scenario has a valid provisional cost before going negative.
            // This remains distinct from the missing-cost rejection test.
        }
    }

    @Test
    public void test03_AverageInvoiceCostDimensionBootstrap() {
        DatabaseIdentity.configured();
        try (Fixture f = new Fixture("TEST03")) {
            f.createProduct();
            assertEquals(1, f.costQuery().count(), "Product save must bootstrap one AIC dimension");
            // Force the missing-row path, scoped to this newly owned product and dimension.
            f.deleteOwnedDimension();
            assertEquals(0, f.costQuery().count(), "Creation precondition: no dimension row");

            MCost created = f.bootstrap();
            f.assertDimension(created);
            assertEquals(1, f.costQuery().count(), "Bootstrap must persist exactly one row");
            assertDecimal("0", created.getCurrentQty(), "Initial quantity");
            assertDecimal("0", created.getCumulatedQty(), "Initial accumulated quantity");
            assertDecimal("0", created.getCumulatedAmt(), "Initial accumulated amount");
            assertDecimal("0", created.getCurrentCostPrice(), "Initial price");

            // M_Cost uses a composite key, not a surrogate M_Cost_ID. A persisted marker
            // proves repeat calls retrieve the same dimension without reinitializing it.
            created.setCurrentCostPrice(new BigDecimal("17.25"));
            created.saveEx();
            MCost again = f.bootstrap();
            f.assertDimension(again);
            assertDecimal("17.25", again.getCurrentCostPrice(), "Idempotent dimension lookup");
            assertEquals(1, f.costQuery().count(), "Repeat bootstrap must not duplicate the row");
            MCost persisted = f.costQuery().firstOnly();
            assertNotNull(persisted, "Dimension must be visible via a fresh database query");
            f.assertDimension(persisted);
            assertDecimal("17.25", persisted.getCurrentCostPrice(), "Persisted identity marker");
        } // Fixture.close checks rollback and absence through a new transaction, even on failure.
    }

    @Test
    public void test04_ReplenishmentWithDifferentCost() {
        try (Fixture f = new Fixture("TEST04")) {
            Timestamp first = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            f.assertDocumentPreconditions(first);
            f.createProduct();
            f.createPartner();
            f.movement(false, "10", "30", first);
            f.assertCostState("10", "300", "30");
            f.movement(true, "20", "30", Timestamp.valueOf(TEST_YEAR + "-01-02 00:00:00"));
            f.assertCostState("-10", "-300", "30");
            MInOutLine receipt = f.movement(false, "20", "40", Timestamp.valueOf(TEST_YEAR + "-01-03 00:00:00"));
            f.assertCostState("10", "400", "40");
            f.invoice(receipt, "20", "40", Timestamp.valueOf(TEST_YEAR + "-01-03 00:00:00"));
            f.assertCostState("10", "400", "40");
        }
    }

    @ParameterizedTest
    @CsvSource({"5,40,-5,-150,30", "10,40,0,0,30", "20,20,10,200,20", "20,30,10,300,30"})
    public void test05_ReplenishmentVariants(String receiptQty, String price,
            String finalQty, String finalValue, String finalPrice) {
        try (Fixture f = new Fixture("TEST05")) {
            Timestamp first = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            f.assertDocumentPreconditions(first);
            f.createProduct();
            f.createPartner();
            f.movement(false, "10", "30", first);
            f.movement(true, "20", "30", Timestamp.valueOf(TEST_YEAR + "-01-02 00:00:00"));
            MInOutLine receipt = f.movement(false, receiptQty, price, Timestamp.valueOf(TEST_YEAR + "-01-03 00:00:00"));
            f.assertCostState(finalQty, finalValue, finalPrice);
            f.invoice(receipt, receiptQty, price, Timestamp.valueOf(TEST_YEAR + "-01-03 00:00:00"));
            f.assertCostState(finalQty, finalValue, finalPrice);
        }
    }

    @Test
    public void test06_ReprocessingReceiptIsIdempotent() {
        try (Fixture f = new Fixture("TEST06")) {
            Timestamp first = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            f.assertDocumentPreconditions(first);
            f.createProduct();
            f.createPartner();
            MInOutLine initial = f.movement(false, "10", "30", first);
            f.movement(true, "20", "30", Timestamp.valueOf(TEST_YEAR + "-01-02 00:00:00"));
            MInOutLine receipt = f.movement(false, "20", "40", Timestamp.valueOf(TEST_YEAR + "-01-03 00:00:00"));
            f.assertCostState("10", "400", "40");
            int detailCount = f.count("M_CostDetail");
            MTransaction transaction = new Query(f.ctx, MTransaction.Table_Name,
                    "M_InOutLine_ID=?", f.name).setParameters(receipt.get_ID()).firstOnly();
            CostEngineFactory.getCostEngine(CLIENT_ID).createCostDetail(f.schema,
                    (MCostType) f.schema.getM_CostType(), f.material, transaction, receipt, true);
            f.assertCostState("10", "400", "40");
            assertEquals(detailCount, f.count("M_CostDetail"), "Reprocessing must not duplicate cost details");
            MTransaction original = new Query(f.ctx, MTransaction.Table_Name,
                    "M_InOutLine_ID=?", f.name).setParameters(initial.get_ID()).firstOnly();
            CostEngineFactory.getCostEngine(CLIENT_ID).createCostDetail(f.schema,
                    (MCostType) f.schema.getM_CostType(), f.material, original, initial, true);
            f.assertCostState("10", "400", "40");
            assertEquals(detailCount, f.count("M_CostDetail"), "Replaying the first receipt must preserve the chain");
        }
    }

    @Test
    public void test07_AccountingRecognizesCoveredCostDifference() {
        try (Fixture f = new Fixture("TEST07")) {
            Timestamp first = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            f.assertDocumentPreconditions(first);
            f.createProduct();
            // Doc.getValidCombinationId reads partner accounts outside the document
            // transaction. Use the seed partner on this disposable clone; its complete
            // original row is snapshotted and checked again after rollback.
            f.useExistingPartner();
            MInOutLine initial = f.movement(false, "10", "30", first);
            f.post(MInOut.Table_ID, initial.getM_InOut_ID());
            MInOutLine sale = f.movement(true, "20", "30", Timestamp.valueOf(TEST_YEAR + "-01-02 00:00:00"));
            f.post(MInOut.Table_ID, sale.getM_InOut_ID());
            MInOutLine receipt = f.movement(false, "20", "40", Timestamp.valueOf(TEST_YEAR + "-01-03 00:00:00"));
            f.post(MInOut.Table_ID, receipt.getM_InOut_ID());
            MInvoiceLine invoice = f.invoice(receipt, "20", "40", Timestamp.valueOf(TEST_YEAR + "-01-03 00:00:00"));
            f.post(MInvoice.Table_ID, invoice.getC_Invoice_ID());
            MMatchInv match = new Query(f.ctx, MMatchInv.Table_Name,
                    "C_InvoiceLine_ID=? AND M_InOutLine_ID=?", f.name)
                    .setParameters(invoice.get_ID(), receipt.get_ID()).firstOnly();
            assertNotNull(match);
            f.post(MMatchInv.Table_ID, match.get_ID());
            f.assertCostState("10", "400", "40");
            assertDecimal("400", f.accountBalance("P_Asset_Acct"), "Posted inventory asset");
            assertDecimal("600", f.accountBalance("P_Cogs_Acct"), "Provisional shipment COGS");
            assertDecimal("100", f.accountBalance("P_CostAdjustment_Acct"), "Covered cost difference");
        }
    }

    @Test
    public void test08_MultipleReceiptsSettleOnlyRemainingNegativeQuantity() {
        try (Fixture f = new Fixture("TEST08")) {
            Timestamp day = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            f.assertDocumentPreconditions(day);
            f.createProduct();
            f.createPartner();
            f.movement(false, "10", "30", day);
            f.movement(true, "20", "30", Timestamp.valueOf(TEST_YEAR + "-01-02 00:00:00"));
            MInOutLine partial = f.movement(false, "5", "40", Timestamp.valueOf(TEST_YEAR + "-01-03 00:00:00"));
            f.invoice(partial, "5", "40", Timestamp.valueOf(TEST_YEAR + "-01-03 00:00:00"));
            f.assertCostState("-5", "-150", "30");
            MInOutLine remaining = f.movement(false, "15", "50", Timestamp.valueOf(TEST_YEAR + "-01-04 00:00:00"));
            f.invoice(remaining, "15", "50", Timestamp.valueOf(TEST_YEAR + "-01-04 00:00:00"));
            f.assertCostState("10", "500", "50");
        }
    }

    @Test
    public void test09_ReversingReplenishmentRestoresProvisionalValue() {
        try (Fixture f = new Fixture("TEST09")) {
            Timestamp day = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            f.assertDocumentPreconditions(day);
            f.createProduct();
            f.createPartner();
            f.movement(false, "10", "30", day);
            f.movement(true, "20", "30", Timestamp.valueOf(TEST_YEAR + "-01-02 00:00:00"));
            MInOutLine receipt = f.movement(false, "20", "40", Timestamp.valueOf(TEST_YEAR + "-01-03 00:00:00"));
            f.assertCostState("10", "400", "40");
            MInOut document = new MInOut(f.ctx, receipt.getM_InOut_ID(), f.name);
            assertTrue(document.reverseCorrectIt(), document.getProcessMsg());
            document.saveEx();
            f.assertCostState("-10", "-300", "30");
        }
    }

    @ParameterizedTest
    @CsvSource({"20,50,500,50,200", "5,50,425,42.5,50", "20,30,300,30,0"})
    public void test10_InvoicePriceDifferenceIsSplitBetweenStockAndCoveredIssues(
            String invoiceQty, String invoicePrice, String value, String average, String adjustment) {
        try (Fixture f = new Fixture("TEST10")) {
            Timestamp day = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            f.assertDocumentPreconditions(day);
            f.createProduct();
            f.useExistingPartner();
            MInOutLine initial = f.movement(false, "10", "30", day);
            f.post(MInOut.Table_ID, initial.getM_InOut_ID());
            MInOutLine sale = f.movement(true, "20", "30", Timestamp.valueOf(TEST_YEAR + "-01-02 00:00:00"));
            f.post(MInOut.Table_ID, sale.getM_InOut_ID());
            MInOutLine receipt = f.movement(false, "20", "40", Timestamp.valueOf(TEST_YEAR + "-01-03 00:00:00"));
            f.post(MInOut.Table_ID, receipt.getM_InOut_ID());
            MInvoiceLine invoice = f.invoice(receipt, invoiceQty, invoicePrice, Timestamp.valueOf(TEST_YEAR + "-01-04 00:00:00"));
            f.post(MInvoice.Table_ID, invoice.getC_Invoice_ID());
            MMatchInv match = new Query(f.ctx, MMatchInv.Table_Name,
                    "C_InvoiceLine_ID=? AND M_InOutLine_ID=?", f.name)
                    .setParameters(invoice.get_ID(), receipt.get_ID()).firstOnly();
            f.post(MMatchInv.Table_ID, match.get_ID());
            f.assertCostState("10", value, average);
            assertDecimal(value, f.accountBalance("P_Asset_Acct"), "Stock after invoice price difference");
            assertDecimal(adjustment, f.accountBalance("P_CostAdjustment_Acct"), "Invoiced covered cost difference");
            if ("5".equals(invoiceQty)) {
                MInvoiceLine remainder = f.invoice(receipt, "15", invoicePrice,
                        Timestamp.valueOf(TEST_YEAR + "-01-05 00:00:00"));
                f.post(MInvoice.Table_ID, remainder.getC_Invoice_ID());
                MMatchInv remainingMatch = new Query(f.ctx, MMatchInv.Table_Name,
                        "C_InvoiceLine_ID=? AND M_InOutLine_ID=?", f.name)
                        .setParameters(remainder.get_ID(), receipt.get_ID()).firstOnly();
                f.post(MMatchInv.Table_ID, remainingMatch.get_ID());
                f.assertCostState("10", "500", "50");
                assertDecimal("500", f.accountBalance("P_Asset_Acct"), "Stock after both partial invoices");
                assertDecimal("200", f.accountBalance("P_CostAdjustment_Acct"), "Covered difference after both partial invoices");
            }
        }
    }

    @Test
    public void test11_InvoiceReversalRestoresReceiptValuation() {
        try (Fixture f = new Fixture("TEST11")) {
            Timestamp day = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            f.assertDocumentPreconditions(day);
            f.createProduct();
            f.useExistingPartner();
            MInOutLine initial = f.movement(false, "10", "30", day);
            f.post(MInOut.Table_ID, initial.getM_InOut_ID());
            MInOutLine sale = f.movement(true, "20", "30", Timestamp.valueOf(TEST_YEAR + "-01-02 00:00:00"));
            f.post(MInOut.Table_ID, sale.getM_InOut_ID());
            MInOutLine receipt = f.movement(false, "20", "40", Timestamp.valueOf(TEST_YEAR + "-01-03 00:00:00"));
            f.post(MInOut.Table_ID, receipt.getM_InOut_ID());
            MInvoiceLine line = f.invoice(receipt, "20", "50", Timestamp.valueOf(TEST_YEAR + "-01-04 00:00:00"));
            f.post(MInvoice.Table_ID, line.getC_Invoice_ID());
            MMatchInv match = new Query(f.ctx, MMatchInv.Table_Name,
                    "C_InvoiceLine_ID=? AND M_InOutLine_ID=?", f.name)
                    .setParameters(line.get_ID(), receipt.get_ID()).firstOnly();
            f.post(MMatchInv.Table_ID, match.get_ID());
            f.assertCostState("10", "500", "50");
            MInvoice invoice = new MInvoice(f.ctx, line.getC_Invoice_ID(), f.name);
            assertTrue(invoice.reverseCorrectIt(), invoice.getProcessMsg());
            invoice.saveEx();
            f.post(MInvoice.Table_ID, invoice.getReversal_ID());
            MMatchInv reversed = new Query(f.ctx, MMatchInv.Table_Name, "Reversal_ID=?", f.name)
                    .setParameters(match.get_ID()).firstOnly();
            assertNotNull(reversed);
            f.post(MMatchInv.Table_ID, reversed.get_ID());
            f.assertCostState("10", "400", "40");
            assertDecimal("400", f.accountBalance("P_Asset_Acct"), "Inventory after invoice reversal");
            assertDecimal("0", f.accountBalance("P_CostAdjustment_Acct"), "Reversed invoice cost difference");
        }
    }

    @Test
    public void test12_ClosedMatchPreventsPartialAccountingReset() {
        try (Fixture f = new Fixture("TEST12")) {
            Timestamp january = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            Timestamp february = Timestamp.valueOf(TEST_YEAR + "-02-01 00:00:00");
            f.assertDocumentPreconditions(january);
            f.assertDocumentPreconditions(february);
            f.createProduct();
            f.useExistingPartner();
            MInOutLine receipt = f.movement(false, "10", "30", january);
            f.post(MInOut.Table_ID, receipt.getM_InOut_ID());
            MInvoiceLine invoice = f.invoice(receipt, "10", "30", february);
            f.post(MInvoice.Table_ID, invoice.getC_Invoice_ID());
            MMatchInv match = new Query(f.ctx, MMatchInv.Table_Name,
                    "C_InvoiceLine_ID=? AND M_InOutLine_ID=?", f.name)
                    .setParameters(invoice.get_ID(), receipt.get_ID()).firstOnly();
            f.post(MMatchInv.Table_ID, match.get_ID());
            List<List<String>> facts = f.historicalRows("Fact_Acct", Timestamp.valueOf(TEST_YEAR + "-03-01 00:00:00"));
            f.closePeriod(february);
            MTransaction transaction = new Query(f.ctx, MTransaction.Table_Name,
                    "M_InOutLine_ID=?", f.name).setParameters(receipt.get_ID()).firstOnly();
            assertFalse(CostEngineFactory.getCostEngine(CLIENT_ID)
                    .clearAccountingForRecalculation(f.schema, transaction), "A closed invoice match must block the whole reset");
            assertEquals(facts, f.historicalRows("Fact_Acct", Timestamp.valueOf(TEST_YEAR + "-03-01 00:00:00")),
                    "An open receipt must keep its facts when a related match is closed");
        }
    }

    @Test
    public void test13_LateInvoiceAfterReceiptPeriodClosed() {
        try (Fixture f = new Fixture("TEST13")) {
            Timestamp january = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            Timestamp february = Timestamp.valueOf(TEST_YEAR + "-02-01 00:00:00");
            f.assertDocumentPreconditions(january);
            f.assertDocumentPreconditions(february);
            f.createProduct();
            f.useExistingPartner();
            MInOutLine initial = f.movement(false, "10", "30", january);
            f.post(MInOut.Table_ID, initial.getM_InOut_ID());
            MInOutLine sale = f.movement(true, "20", "30", Timestamp.valueOf(TEST_YEAR + "-01-02 00:00:00"));
            f.post(MInOut.Table_ID, sale.getM_InOut_ID());
            MInOutLine receipt = f.movement(false, "20", "40", Timestamp.valueOf(TEST_YEAR + "-01-03 00:00:00"));
            f.post(MInOut.Table_ID, receipt.getM_InOut_ID());
            List<List<String>> facts = f.historicalRows("Fact_Acct", february);
            List<List<String>> details = f.historicalRows("M_CostDetail", february);
            f.closePeriod(january);
            MInvoiceLine invoice = f.invoice(receipt, "20", "50", february);
            f.post(MInvoice.Table_ID, invoice.getC_Invoice_ID());
            MMatchInv match = new Query(f.ctx, MMatchInv.Table_Name,
                    "C_InvoiceLine_ID=? AND M_InOutLine_ID=?", f.name)
                    .setParameters(invoice.get_ID(), receipt.get_ID()).firstOnly();
            f.post(MMatchInv.Table_ID, match.get_ID());
            f.assertCostState("10", "500", "50");
            assertDecimal("500", f.accountBalance("P_Asset_Acct"), "Inventory including late invoice price difference");
            assertDecimal("200", f.accountBalance("P_CostAdjustment_Acct"), "Open-period covered price difference");
            assertEquals(facts, f.historicalRows("Fact_Acct", february), "Late invoice must preserve closed facts");
            f.assertHistoricalUnchanged("M_CostDetail", details, february);
        }
    }

    @Test
    public void test14_ReplayCannotRenumberClosedSuccessors() {
        try (Fixture f = new Fixture("TEST14")) {
            Timestamp january = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            Timestamp february = Timestamp.valueOf(TEST_YEAR + "-02-01 00:00:00");
            f.assertDocumentPreconditions(january);
            f.assertDocumentPreconditions(february);
            f.createProduct();
            f.useExistingPartner();
            MInOutLine initial = f.movement(false, "10", "30", january);
            f.movement(true, "20", "30", february);
            f.closePeriod(february);
            List<List<String>> all = f.historicalRows("M_CostDetail", Timestamp.valueOf(TEST_YEAR + "-03-01 00:00:00"));
            List<List<String>> earlier = f.historicalRows("M_CostDetail", february);
            List<List<String>> closed = new ArrayList<>(all);
            closed.removeAll(earlier);
            MTransaction original = new Query(f.ctx, MTransaction.Table_Name,
                    "M_InOutLine_ID=?", f.name).setParameters(initial.get_ID()).firstOnly();
            IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                    CostEngineFactory.getCostEngine(CLIENT_ID).createCostDetail(f.schema,
                            (MCostType) f.schema.getM_CostType(), f.material, original, initial, true));
            assertTrue(error.getMessage().contains("closed period"), error.getMessage());
            // The replay's own open detail may be updated before rejection; its
            // caller must roll the transaction back. Successors must stay untouched.
            List<List<String>> after = f.historicalRows("M_CostDetail", Timestamp.valueOf(TEST_YEAR + "-03-01 00:00:00"));
            after.removeAll(f.historicalRows("M_CostDetail", february));
            assertEquals(closed, after, "Closed successors must not be renumbered or marked Processing");
        }
    }

    @Test
    public void test15_MatchPOReversalUsesItsOwnOriginalMatch() {
        try (Fixture f = new Fixture("TEST15")) {
            Timestamp day = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            f.assertDocumentPreconditions(day);
            f.createProduct();
            f.useExistingPartner();
            MInOutLine receipt = f.movement(false, "10", "30", day);
            MTransaction transaction = new Query(f.ctx, MTransaction.Table_Name,
                    "M_InOutLine_ID=?", f.name).setParameters(receipt.get_ID()).firstOnly();
            MMatchPO original = new Query(f.ctx, MMatchPO.Table_Name,
                    "M_InOutLine_ID=? AND C_InvoiceLine_ID IS NULL", f.name)
                    .setParameters(receipt.get_ID()).firstOnly();
            MCostDetail source = new MCostDetail(transaction, SCHEMA_ID,
                    f.schema.getM_CostType_ID(), f.material.get_ID(), Env.ZERO, Env.ZERO, Env.ZERO, f.name);
            source.setM_MatchPO_ID(original.get_ID());
            source.setC_OrderLine_ID(original.getC_OrderLine_ID());
            source.setDateAcct(day);
            source.setSeqNo(100);
            source.setCumulatedQty(new BigDecimal("10"));
            source.setCumulatedAmt(new BigDecimal("300"));
            source.setCurrentCostPrice(new BigDecimal("30"));
            source.saveEx();
            // Isolate the reversal-cost entry point from MatchPO.afterSave(),
            // which would run other costing methods as well. The original match
            // and original cost detail are real, fixture-owned database records.
            MMatchPO reversal = new MMatchPO(f.ctx, 0, f.name);
            reversal.setAD_Org_ID(ORG_ID);
            reversal.setM_Product_ID(f.productId);
            reversal.setC_OrderLine_ID(original.getC_OrderLine_ID());
            reversal.setM_InOutLine_ID(receipt.get_ID());
            reversal.setQty(new BigDecimal("-10"));
            reversal.setReversal_ID(original.get_ID());
            reversal.setDateAcct(day);
            ReversalProbe method = new ReversalProbe();
            method.setCostingMethod(f.schema, transaction, reversal, f.costQuery().firstOnly(),
                    new BigDecimal("30"), Env.ZERO, false);
            MCostDetail result = method.reverseOriginal();
            assertNotNull(result, "Original MatchPO cost must be found");
            assertTrue(result.isReversal());
            assertEquals(original.getC_OrderLine_ID(), result.getC_OrderLine_ID());
            assertEquals(transaction.get_ID(), result.getM_Transaction_ID());
            assertDecimal("0", result.getQty(), "A price match reversal must not move stock");
            assertDecimal("300", method.getNewAccumulatedAmount(result), "Unchanged physical stock value");
            assertDecimal("10", method.getNewAccumulatedQuantity(result), "Unchanged physical stock quantity");
        }
    }

    private static class ReversalProbe extends AverageInvoiceCostingMethod {
        MCostDetail reverseOriginal() {
            costDetail = null;
            createReversalCostDetail();
            return costDetail;
        }
    }

    @Test
    public void test16_BulkRejectsClosedHistoryBeforeDeletingAnything() throws Exception {
        try (Fixture f = new Fixture("TEST16")) {
            Timestamp january = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            f.assertDocumentPreconditions(january);
            f.createProduct();
            f.useExistingPartner();
            f.movement(false, "10", "30", january);
            f.closePeriod(january);
            List<List<String>> costs = f.snapshot("M_Cost", f.name);
            List<List<String>> details = f.snapshot("M_CostDetail", f.name);
            List<List<String>> facts = f.snapshot("Fact_Acct", f.name);
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> new BulkProbe(f, f.productId).generateCostDetail());
            assertTrue(error.getCause().getMessage().contains("closed period"), error.toString());
            assertEquals(costs, f.snapshot("M_Cost", f.name));
            assertEquals(details, f.snapshot("M_CostDetail", f.name));
            assertEquals(facts, f.snapshot("Fact_Acct", f.name));
        }
    }

    @Test
    public void test17_BulkIncludesFirstAndLastDayInvoiceMatches() {
        try (Fixture f = new Fixture("TEST17")) {
            Timestamp january = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            f.assertDocumentPreconditions(january);
            f.createProduct();
            f.useExistingPartner();
            MInOutLine initial = f.movement(false, "10", "30", january);
            MInOutLine sale = f.movement(true, "20", "30", Timestamp.valueOf(TEST_YEAR + "-01-02 00:00:00"));
            MInOutLine receipt = f.movement(false, "20", "40", Timestamp.valueOf(TEST_YEAR + "-01-03 00:00:00"));
            MInvoiceLine invoice = f.invoice(receipt, "20", "50", Timestamp.valueOf(TEST_YEAR + "-01-31 00:00:00"));
            MMatchInv match = new Query(f.ctx, MMatchInv.Table_Name,
                    "C_InvoiceLine_ID=? AND M_InOutLine_ID=?", f.name)
                    .setParameters(invoice.get_ID(), receipt.get_ID()).firstOnly();
            f.post(MInOut.Table_ID, initial.getM_InOut_ID());
            f.post(MInOut.Table_ID, sale.getM_InOut_ID());
            f.post(MInOut.Table_ID, receipt.getM_InOut_ID());
            f.post(MInvoice.Table_ID, invoice.getC_Invoice_ID());
            f.post(MMatchInv.Table_ID, match.get_ID());
            f.assertCostState("10", "500", "50");
            new BulkProbe(f, f.productId).generateCostDetail();
            f.assertCostState("10", "500", "50");
            MCostDetail invoiceCost = new Query(f.ctx, MCostDetail.Table_Name,
                    COST_KEY + " AND C_InvoiceLine_ID=?", f.name)
                    .setParameters(CLIENT_ID, f.productId, SCHEMA_ID, f.schema.getM_CostType_ID(),
                            f.material.get_ID(), invoice.get_ID()).firstOnly();
            assertNotNull(invoiceCost, "The last-day invoice must be rebuilt");
            assertEquals(invoice.get_ID(), invoiceCost.getC_InvoiceLine_ID());
            assertEquals(receipt.get_ID(), invoiceCost.getM_InOutLine_ID());
            f.post(MInOut.Table_ID, initial.getM_InOut_ID());
            f.post(MInOut.Table_ID, sale.getM_InOut_ID());
            f.post(MInOut.Table_ID, receipt.getM_InOut_ID());
            f.post(MInvoice.Table_ID, invoice.getC_Invoice_ID());
            f.post(MMatchInv.Table_ID, match.get_ID());
            assertDecimal("500", f.accountBalance("P_Asset_Acct"), "Regenerated inventory posting");
            assertDecimal("200", f.accountBalance("P_CostAdjustment_Acct"), "Regenerated covered price difference");
        }
    }

    @Test
    public void test18_BulkFailureOnSecondProductRestoresWholeBatch() throws Exception {
        try (Fixture f = new Fixture("TEST18")) {
            Timestamp january = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            f.assertDocumentPreconditions(january);
            MProductCategory category = new MProductCategory(f.ctx, 0, f.name);
            category.setName(f.owner);
            category.saveEx();
            f.ownedCategoryId = category.get_ID();
            f.createProduct();
            f.useExistingPartner();
            MInOutLine firstReceipt = f.movement(false, "10", "30", january);
            f.post(MInOut.Table_ID, firstReceipt.getM_InOut_ID());
            f.createProduct("-second");
            MInOutLine secondReceipt = f.movement(false, "20", "40", january);
            f.post(MInOut.Table_ID, secondReceipt.getM_InOut_ID());
            assertEquals(2, DB.getSQLValueEx(f.name,
                    "SELECT COUNT(*) FROM RV_Transaction WHERE AD_Client_ID=? AND M_Product_Category_ID=? AND DateAcct>=? AND DateAcct<?",
                    CLIENT_ID, f.ownedCategoryId, january, Timestamp.valueOf(TEST_YEAR + "-02-01 00:00:00")),
                    "The whole selection must consist of our two fixture products");
            List<List<String>> costs = f.snapshot("M_Cost", f.name);
            List<List<String>> details = f.snapshot("M_CostDetail", f.name);
            List<List<String>> facts = f.snapshot("Fact_Acct", f.name);
            List<List<String>> headers = f.snapshot("M_InOut", f.name);
            BulkProbe process = new BulkProbe(f, 0);
            process.failOnProduct = f.productId;
            IllegalStateException error = assertThrows(IllegalStateException.class, process::generateCostDetail);
            assertEquals("Injected second-product failure", error.getCause().getMessage());
            assertEquals(2, process.visitedProducts.size(), "A completed first product must precede the failure");
            assertEquals(costs, f.snapshot("M_Cost", f.name), "No first-product change may survive a later failure");
            assertEquals(details, f.snapshot("M_CostDetail", f.name));
            assertEquals(facts, f.snapshot("Fact_Acct", f.name));
            assertEquals(headers, f.snapshot("M_InOut", f.name), "Posting flags must be restored too");
        }
    }

    @Test
    public void test19_BulkRejectsClosedRelatedInvoiceMatch() throws Exception {
        try (Fixture f = new Fixture("TEST19")) {
            Timestamp january = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            Timestamp february = Timestamp.valueOf(TEST_YEAR + "-02-01 00:00:00");
            f.assertDocumentPreconditions(january);
            f.assertDocumentPreconditions(february);
            f.createProduct();
            f.useExistingPartner();
            MInOutLine receipt = f.movement(false, "10", "30", january);
            f.post(MInOut.Table_ID, receipt.getM_InOut_ID());
            MInvoiceLine invoice = f.invoice(receipt, "10", "40", february);
            f.post(MInvoice.Table_ID, invoice.getC_Invoice_ID());
            MMatchInv match = new Query(f.ctx, MMatchInv.Table_Name,
                    "C_InvoiceLine_ID=? AND M_InOutLine_ID=?", f.name)
                    .setParameters(invoice.get_ID(), receipt.get_ID()).firstOnly();
            f.post(MMatchInv.Table_ID, match.get_ID());
            f.closePeriod(february);
            List<List<String>> costs = f.snapshot("M_Cost", f.name);
            List<List<String>> details = f.snapshot("M_CostDetail", f.name);
            List<List<String>> facts = f.snapshot("Fact_Acct", f.name);
            BulkProbe process = new BulkProbe(f, f.productId);
            process.through(Timestamp.valueOf(TEST_YEAR + "-02-28 00:00:00"));
            IllegalStateException error = assertThrows(IllegalStateException.class, process::generateCostDetail);
            assertTrue(error.getCause().getMessage().contains("closed period"), error.toString());
            assertEquals(costs, f.snapshot("M_Cost", f.name));
            assertEquals(details, f.snapshot("M_CostDetail", f.name));
            assertEquals(facts, f.snapshot("Fact_Acct", f.name));
        }
    }

    @Test
    public void test20_BulkRejectsAnIncompleteCostingTail() throws Exception {
        try (Fixture f = new Fixture("TEST20")) {
            Timestamp january = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            Timestamp february = Timestamp.valueOf(TEST_YEAR + "-02-01 00:00:00");
            f.assertDocumentPreconditions(january);
            f.assertDocumentPreconditions(february);
            f.createProduct();
            f.useExistingPartner();
            f.movement(false, "10", "30", january);
            f.movement(true, "1", "30", february);
            List<List<String>> costs = f.snapshot("M_Cost", f.name);
            List<List<String>> details = f.snapshot("M_CostDetail", f.name);
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> new BulkProbe(f, f.productId).generateCostDetail());
            assertTrue(error.getCause().getMessage().contains("complete costing tail"), error.toString());
            assertEquals(costs, f.snapshot("M_Cost", f.name));
            assertEquals(details, f.snapshot("M_CostDetail", f.name));
        }
    }

    @Test
    public void test21_QuantityAggregationDoesNotMixAccountingSchemas() {
        try (Fixture f = new Fixture("TEST21")) {
            Timestamp january = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            f.assertDocumentPreconditions(january);
            f.createProduct();
            f.useExistingPartner();
            MInOutLine receipt = f.movement(false, "10", "30", january);
            MCost dimension = f.costQuery().firstOnly();
            MAcctSchema otherSchema = new MAcctSchema(f.ctx, 0, f.name);
            PO.copyValues(f.schema, otherSchema);
            otherSchema.setName(f.owner + "-schema");
            otherSchema.saveEx();
            assertNotEquals(SCHEMA_ID, otherSchema.get_ID(), "The fixture must create a distinct schema");
            MTransaction transaction = new Query(f.ctx, MTransaction.Table_Name,
                    "M_InOutLine_ID=?", f.name).setParameters(receipt.get_ID()).firstOnly();
            MCostDetail other = new MCostDetail(transaction, otherSchema.get_ID(),
                    f.schema.getM_CostType_ID(), f.material.get_ID(), new BigDecimal("3000"),
                    Env.ZERO, new BigDecimal("100"), f.name);
            other.setDateAcct(january);
            other.setSeqNo(10);
            other.saveEx();
            assertDecimal("110", DB.getSQLValueBDEx(f.name,
                    "SELECT SUM(Qty) FROM M_CostDetail WHERE AD_Client_ID=? AND M_Product_ID=?"
                    + " AND M_CostType_ID=? AND M_CostElement_ID=?",
                    CLIENT_ID, f.productId, f.schema.getM_CostType_ID(), f.material.get_ID()),
                    "An unscoped aggregation would mix the two schemas");
            assertDecimal("10", MCostDetail.getQtyOnHandByASIAndSeqNo(dimension, transaction.getM_AttributeSetInstance_ID(), 10000,
                    MAcctSchema.COSTINGLEVEL_Client, f.name), "The engine must isolate the current schema");
        }
    }

    @ParameterizedTest
    @CsvSource({"0", "10"})
    public void test22_SalesWithZeroCostAreRejected(String openingQty) throws Exception {
        try (Fixture f = new Fixture("TEST22")) {
            Timestamp day = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            f.assertDocumentPreconditions(day);
            f.createProduct();
            f.useExistingPartner();
            if (!"0".equals(openingQty)) f.movement(false, openingQty, "0", day);
            MInOutLine line = f.draftMovement(true, "1", "50", day);
            int detailsBefore = f.count("M_CostDetail");
            int movementsBefore = f.count("M_Transaction");
            java.sql.Savepoint savepoint = f.transaction.getConnection().setSavepoint();
            MInOut shipment = line.getParent();
            assertFalse(shipment.processIt(DocAction.ACTION_Complete), "Zero-cost shipment must not complete");
            assertNotNull(shipment.getProcessMsg());
            assertTrue(shipment.getProcessMsg().contains("ZERO_SALES_COST"), shipment.getProcessMsg());
            f.transaction.getConnection().rollback(savepoint);
            assertEquals(movementsBefore, f.count("M_Transaction"), "Rollback must remove the rejected movement");
            assertEquals(detailsBefore, f.count("M_CostDetail"), "Rollback must remove rejected costing");
            assertEquals(0, f.count("Fact_Acct"));
            assertFalse(new MInOut(f.ctx, line.getM_InOut_ID(), f.name).isProcessed());
        }
    }

    @Test
    public void test23_NegativeStockWithKnownProvisionalCostRemainsAllowed() {
        try (Fixture f = new Fixture("TEST23")) {
            Timestamp day = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            f.assertDocumentPreconditions(day);
            f.createProduct();
            f.useExistingPartner();
            f.movement(false, "1", "30", day);
            f.movement(true, "1", "50", day);
            MInOutLine sale = f.movement(true, "1", "50", day);
            f.assertCostState("-1", "-30", "30");
            f.post(MInOut.Table_ID, sale.getM_InOut_ID());
            assertDecimal("30", f.accountBalance("P_Cogs_Acct"), "Known provisional cost must reach COGS");
        }
    }

    @ParameterizedTest
    @CsvSource({"ZERO", "MISSING"})
    public void test24_PostingRejectsLegacyZeroOrMissingCosts(String state) {
        try (Fixture f = new Fixture("TEST24")) {
            Timestamp day = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            f.assertDocumentPreconditions(day);
            f.createProduct();
            f.useExistingPartner();
            f.movement(false, "1", "30", day);
            MInOutLine sale = f.movement(true, "1", "50", day);
            // Reproduce imported/legacy bad details only for this owned line.
            String condition = " WHERE AD_Client_ID=? AND M_Product_ID=? AND M_InOutLine_ID=?"
                    + " AND C_AcctSchema_ID=? AND M_CostType_ID=?";
            Object[] key = {CLIENT_ID, f.productId, sale.get_ID(), SCHEMA_ID, f.schema.getM_CostType_ID()};
            int componentCount = DB.getSQLValueEx(f.name, "SELECT COUNT(*) FROM M_CostDetail" + condition, key);
            assertTrue(componentCount > 0);
            if ("MISSING".equals(state)) {
                assertEquals(componentCount, DB.executeUpdateEx("DELETE FROM M_CostDetail" + condition, key, f.name));
            } else {
                assertEquals(componentCount, DB.executeUpdateEx("UPDATE M_CostDetail SET CostAmt=0, CostAdjustment=0,"
                        + " CostAmtLL=0, CostAdjustmentLL=0, Amt=0, AmtLL=0" + condition, key, f.name));
            }
            String error = Doc.postImmediate(new MAcctSchema[] {f.schema}, MInOut.Table_ID,
                    sale.getM_InOut_ID(), true, f.name);
            assertNotNull(error, "Posting must reject a legacy line without cost");
            assertTrue(error.contains("ZERO_SALES_COST"), error);
            assertEquals(0, f.count("Fact_Acct"));
            assertFalse(new MInOut(f.ctx, sale.getM_InOut_ID(), f.name).isPosted());
        }
    }

    @Test
    public void test25_ZeroOptionalComponentsAndLowerLevelCostAreHandledTogether() {
        try (Fixture f = new Fixture("TEST25")) {
            f.createProduct();
            Timestamp day = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            MCostDetail lowerLevel = new MCostDetail(f.ctx, 0, f.name);
            lowerLevel.setCostAmtLL(new BigDecimal("-30"));
            MCostDetail optionalZero = new MCostDetail(f.ctx, 0, f.name);
            List<MCostDetail> components = Arrays.asList(lowerLevel, optionalZero);
            assertDoesNotThrow(() -> CostEngine.requireSalesCost(f.schema, f.productId, 0,
                    f.schema.getM_CostType_ID(), day, components));
            lowerLevel.setCostAmtLL(Env.ZERO);
            ZeroSalesCostException error = assertThrows(ZeroSalesCostException.class, () ->
                    CostEngine.requireSalesCost(f.schema, f.productId, 0, f.schema.getM_CostType_ID(), day, components));
            assertTrue(error.getMessage().contains("ZERO_SALES_COST"));
            assertThrows(ZeroSalesCostException.class, () -> CostEngine.requireSalesCost(f.schema,
                    f.productId, 0, f.schema.getM_CostType_ID(), day, java.util.Collections.emptyList()));
        }
    }

    @Test
    public void test26_ZeroCostSecondLineRejectsWholeShipment() throws Exception {
        try (Fixture f = new Fixture("TEST26")) {
            Timestamp day = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            f.assertDocumentPreconditions(day);
            f.createProduct();
            f.useExistingPartner();
            f.movement(false, "1", "30", day);
            MInOutLine first = f.draftMovement(true, "1", "50", day);
            MInOut shipment = first.getParent();
            f.createProduct("-zero");
            MOrderLine secondOrderLine = f.order(true, "1", "50", day);
            MInOutLine second = new MInOutLine(shipment);
            second.setC_OrderLine_ID(secondOrderLine.get_ID());
            second.setM_Product_ID(f.productId);
            second.setC_UOM_ID(f.product.getC_UOM_ID());
            second.setM_Locator_ID(LOCATOR_ID);
            second.setMovementQty(Env.ONE);
            second.saveEx();
            List<List<String>> costs = f.snapshot("M_Cost", f.name);
            List<List<String>> details = f.snapshot("M_CostDetail", f.name);
            List<List<String>> movements = f.snapshot("M_Transaction", f.name);
            List<List<String>> storage = f.snapshot("M_Storage", f.name);
            java.sql.Savepoint savepoint = f.transaction.getConnection().setSavepoint();
            assertFalse(shipment.processIt(DocAction.ACTION_Complete));
            assertTrue(shipment.getProcessMsg().contains("ZERO_SALES_COST"), shipment.getProcessMsg());
            f.transaction.getConnection().rollback(savepoint);
            assertEquals(costs, f.snapshot("M_Cost", f.name));
            assertEquals(details, f.snapshot("M_CostDetail", f.name));
            assertEquals(movements, f.snapshot("M_Transaction", f.name));
            assertEquals(storage, f.snapshot("M_Storage", f.name));
            assertEquals(0, f.count("Fact_Acct"));
            assertFalse(new MInOut(f.ctx, shipment.get_ID(), f.name).isProcessed());
        }
    }

    @Test
    public void test27_DeferredCostingCannotPostOrRegenerateAZeroCostSale() throws Exception {
        try (Fixture f = new Fixture("TEST27")) {
            Timestamp day = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            f.assertDocumentPreconditions(day);
            f.createProduct();
            f.useExistingPartner();
            MClient client = MClient.get(f.ctx);
            boolean immediate = client.isCostImmediate();
            try {
                // Only the dedicated test JVM cache changes; no client row is saved.
                client.setIsCostImmediate(false);
                MInOutLine sale = f.movement(true, "1", "50", day);
                assertEquals(0, f.count("M_CostDetail"), "The deferred path must not have calculated costs yet");
                String postingError = Doc.postImmediate(new MAcctSchema[] {f.schema}, MInOut.Table_ID,
                        sale.getM_InOut_ID(), true, f.name);
                assertNotNull(postingError);
                assertTrue(postingError.contains("ZERO_SALES_COST"), postingError);
                List<List<String>> costs = f.snapshot("M_Cost", f.name);
                List<List<String>> details = f.snapshot("M_CostDetail", f.name);
                IllegalStateException regenerationError = assertThrows(IllegalStateException.class,
                        () -> new BulkProbe(f, f.productId).generateCostDetail());
                assertTrue(regenerationError.getCause() instanceof ZeroSalesCostException, regenerationError.toString());
                assertEquals(costs, f.snapshot("M_Cost", f.name));
                assertEquals(details, f.snapshot("M_CostDetail", f.name));
                assertEquals(0, f.count("Fact_Acct"));
                assertFalse(new MInOut(f.ctx, sale.getM_InOut_ID(), f.name).isPosted());
            } finally {
                client.setIsCostImmediate(immediate);
            }
        }
    }

    @Test
    public void test28_ParallelCostRegenerationDoesNotValidateAnUnselectedAccountingType() {
        try (Fixture f = new Fixture("TEST28")) {
            Timestamp day = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            f.assertDocumentPreconditions(day);
            f.createProduct();
            f.useExistingPartner();
            MClient client = MClient.get(f.ctx);
            boolean immediate = client.isCostImmediate();
            try {
                client.setIsCostImmediate(false);
                f.movement(true, "1", "50", day);
                MCostType parallel = new Query(f.ctx, MCostType.Table_Name,
                        "AD_Client_ID=? AND CostingMethod=? AND M_CostType_ID<>?", f.name)
                        .setParameters(CLIENT_ID, MCostType.COSTINGMETHOD_StandardCosting,
                                f.schema.getM_CostType_ID()).setOnlyActiveRecords(true).firstOnly();
                BulkProbe process = new BulkProbe(f, f.productId);
                process.usingType(parallel.get_ID());
                assertDoesNotThrow(() -> { process.generateCostDetail(); });
                assertEquals(0, new Query(f.ctx, MCostDetail.Table_Name, COST_KEY, f.name)
                        .setParameters(f.dimensionParameters()).count(), "The accounting type was not selected");
                assertEquals(0, f.count("Fact_Acct"), "A parallel calculation must not create accounting");
            } finally { client.setIsCostImmediate(immediate); }
        }
    }

    @ParameterizedTest
    @CsvSource({"0,20,800,40,0", "10,10,400,40,100", "20,0,0,30,200"})
    public void test29_LateLandedCostIsSelectedWithoutRebuildingClosedReceipt(String sold,
            String quantity, String value, String average, String expense) {
        try (Fixture f = new Fixture("TEST29")) {
            Timestamp january = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            Timestamp february = Timestamp.valueOf(TEST_YEAR + "-02-01 00:00:00");
            f.assertDocumentPreconditions(january);
            f.assertDocumentPreconditions(february);
            f.createProduct();
            f.useExistingPartner();
            MInOutLine receipt = f.movement(false, "20", "30", january);
            f.post(MInOut.Table_ID, receipt.getM_InOut_ID());
            if (!"0".equals(sold)) {
                MInOutLine sale = f.movement(true, sold, "50", Timestamp.valueOf(TEST_YEAR + "-01-02 00:00:00"));
                f.post(MInOut.Table_ID, sale.getM_InOut_ID());
            }
            DB.executeUpdateEx("UPDATE M_CostDetail SET Updated=? WHERE AD_Client_ID=? AND M_Product_ID=? AND DateAcct<?",
                    new Object[]{Timestamp.valueOf("2000-01-01 00:00:00"), CLIENT_ID, f.productId, february}, f.name);
            List<List<String>> closedDetails = f.historicalRows("M_CostDetail", february);
            List<List<String>> closedFacts = f.historicalRows("Fact_Acct", february);
            List<List<String>> closedHeaders = f.snapshotUnchecked("M_InOut");
            f.closePeriod(january);
            MLandedCostAllocation allocation = f.pendingLandedCost(receipt, "200", february);
            BulkProbe process = new BulkProbe(f, f.productId);
            process.from(february);
            process.through(Timestamp.valueOf(TEST_YEAR + "-02-28 00:00:00"));
            process.generateCostDetail();
            MCostDetail event = new Query(f.ctx, MCostDetail.Table_Name, COST_KEY
                    + " AND C_LandedCostAllocation_ID=?", f.name).setParameters(CLIENT_ID, f.productId,
                    SCHEMA_ID, f.schema.getM_CostType_ID(), f.material.get_ID(), allocation.get_ID()).firstOnly();
            assertNotNull(event, "February must select the allocation even though its receipt is in closed January");
            assertEquals(february, event.getDateAcct());
            assertDecimal("0", event.getQty(), "Late cost must not create physical stock");
            f.assertCostState(quantity, value, average);
            f.assertHistoricalUnchanged("M_CostDetail", closedDetails, february);
            assertEquals(closedFacts, f.historicalRows("Fact_Acct", february));
            assertEquals(closedHeaders, f.snapshotUnchecked("M_InOut"));
            f.post(MInvoice.Table_ID, allocation.getInvoice().get_ID());
            assertDecimal(value, f.accountBalance("P_Asset_Acct"), "Inventory after late freight");
            assertDecimal(expense, f.accountBalance("P_CostAdjustment_Acct"), "Sold portion of freight");
            List<List<String>> allDetails = f.snapshotUnchecked("M_CostDetail");
            List<List<String>> allFacts = f.snapshotUnchecked("Fact_Acct");
            List<List<String>> invoices = f.snapshotUnchecked("C_Invoice");
            process.generateCostDetail();
            assertEquals(allDetails, f.snapshotUnchecked("M_CostDetail"), "Repetition must preserve every detail row");
            assertEquals(allFacts, f.snapshotUnchecked("Fact_Acct"), "Repetition must preserve posted facts");
            assertEquals(invoices, f.snapshotUnchecked("C_Invoice"), "Repetition must leave the invoice posted");
        }
    }

    @Test
    public void test30_LateLandedCostCreditHasTheOppositeAccountingSign() {
        try (Fixture f = new Fixture("TEST30")) {
            Timestamp january = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            Timestamp february = Timestamp.valueOf(TEST_YEAR + "-02-01 00:00:00");
            f.assertDocumentPreconditions(january);
            f.assertDocumentPreconditions(february);
            f.createProduct(); f.useExistingPartner();
            MInOutLine receipt = f.movement(false, "20", "30", january);
            f.post(MInOut.Table_ID, receipt.getM_InOut_ID());
            MInOutLine sale = f.movement(true, "10", "50", Timestamp.valueOf(TEST_YEAR + "-01-02 00:00:00"));
            f.post(MInOut.Table_ID, sale.getM_InOut_ID());
            List<List<String>> details = f.historicalRows("M_CostDetail", february);
            List<List<String>> facts = f.historicalRows("Fact_Acct", february);
            f.closePeriod(january);
            MLandedCostAllocation allocation = f.pendingLandedCost(receipt, "200", february, true);
            BulkProbe process = new BulkProbe(f, f.productId);
            process.from(february); process.through(Timestamp.valueOf(TEST_YEAR + "-02-28 00:00:00"));
            process.generateCostDetail();
            f.assertCostState("10", "200", "20");
            f.post(MInvoice.Table_ID, allocation.getInvoice().get_ID());
            assertDecimal("200", f.accountBalance("P_Asset_Acct"), "Credit reduces remaining inventory cost");
            assertDecimal("-100", f.accountBalance("P_CostAdjustment_Acct"), "Credit reverses the sold portion");
            f.assertHistoricalUnchanged("M_CostDetail", details, february);
            assertEquals(facts, f.historicalRows("Fact_Acct", february));
        }
    }

    @Test
    public void test31_TwoLateCostsAndDirectReplayKeepTheClosedReceiptUnchanged() {
        try (Fixture f = new Fixture("TEST31")) {
            Timestamp january = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            Timestamp february = Timestamp.valueOf(TEST_YEAR + "-02-01 00:00:00");
            f.assertDocumentPreconditions(january); f.assertDocumentPreconditions(february);
            f.createProduct(); f.useExistingPartner();
            MInOutLine receipt = f.movement(false, "20", "30", january);
            f.post(MInOut.Table_ID, receipt.getM_InOut_ID());
            MInOutLine sale = f.movement(true, "10", "50", Timestamp.valueOf(TEST_YEAR + "-01-02 00:00:00"));
            f.post(MInOut.Table_ID, sale.getM_InOut_ID());
            List<List<String>> details = f.historicalRows("M_CostDetail", february);
            List<List<String>> facts = f.historicalRows("Fact_Acct", february);
            f.closePeriod(january);
            MLandedCostAllocation first = f.pendingLandedCost(receipt, "200", february);
            MLandedCostAllocation second = f.pendingLandedCost(receipt, "100", Timestamp.valueOf(TEST_YEAR + "-02-02 00:00:00"));
            BulkProbe process = new BulkProbe(f, f.productId);
            process.from(february); process.through(Timestamp.valueOf(TEST_YEAR + "-02-28 00:00:00"));
            process.generateCostDetail();
            f.assertCostState("10", "450", "45");
            f.post(MInvoice.Table_ID, first.getInvoice().get_ID());
            f.post(MInvoice.Table_ID, second.getInvoice().get_ID());
            int count = f.count("M_CostDetail");
            MTransaction transaction = new Query(f.ctx, MTransaction.Table_Name,
                    "M_InOutLine_ID=?", f.name).setParameters(receipt.get_ID()).firstOnly();
            CostEngineFactory.getCostEngine(CLIENT_ID).createCostDetail(f.schema,
                    (MCostType) f.schema.getM_CostType(), f.material, transaction, first, true);
            f.assertCostState("10", "450", "45");
            assertEquals(count, f.count("M_CostDetail"));
            f.post(MInvoice.Table_ID, second.getInvoice().get_ID());
            assertDecimal("450", f.accountBalance("P_Asset_Acct"), "Both allocations survive replay");
            assertDecimal("150", f.accountBalance("P_CostAdjustment_Acct"), "Both sold portions survive replay");
            f.assertHistoricalUnchanged("M_CostDetail", details, february);
            assertEquals(facts, f.historicalRows("Fact_Acct", february));
        }
    }

    @Test
    public void test32_ClosedLateInvoicePreventsAnyCostOrAccountingChanges() {
        try (Fixture f = new Fixture("TEST32")) {
            Timestamp january = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            Timestamp february = Timestamp.valueOf(TEST_YEAR + "-02-01 00:00:00");
            f.assertDocumentPreconditions(january); f.assertDocumentPreconditions(february);
            f.createProduct(); f.useExistingPartner();
            MInOutLine receipt = f.movement(false, "20", "30", january);
            f.closePeriod(january);
            MLandedCostAllocation allocation = f.pendingLandedCost(receipt, "200", february);
            f.post(MInvoice.Table_ID, allocation.getInvoice().get_ID());
            f.closePeriod(february);
            List<List<String>> costs = f.snapshotUnchecked("M_Cost");
            List<List<String>> details = f.snapshotUnchecked("M_CostDetail");
            List<List<String>> facts = f.snapshotUnchecked("Fact_Acct");
            List<List<String>> invoices = f.snapshotUnchecked("C_Invoice");
            BulkProbe process = new BulkProbe(f, f.productId);
            process.from(february); process.through(Timestamp.valueOf(TEST_YEAR + "-02-28 00:00:00"));
            IllegalStateException error = assertThrows(IllegalStateException.class, process::generateCostDetail);
            assertTrue(error.getCause().getMessage().contains("closed period"), error.toString());
            assertEquals(costs, f.snapshotUnchecked("M_Cost"));
            assertEquals(details, f.snapshotUnchecked("M_CostDetail"));
            assertEquals(facts, f.snapshotUnchecked("Fact_Acct"));
            assertEquals(invoices, f.snapshotUnchecked("C_Invoice"));
        }
    }

    @Test
    public void test33_ReplayCannotTouchALandedCostInAClosedSuccessorPeriod() throws Exception {
        try (Fixture f = new Fixture("TEST33")) {
            Timestamp january = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            Timestamp february = Timestamp.valueOf(TEST_YEAR + "-02-01 00:00:00");
            Timestamp march = Timestamp.valueOf(TEST_YEAR + "-03-01 00:00:00");
            f.assertDocumentPreconditions(january); f.assertDocumentPreconditions(february);
            f.assertDocumentPreconditions(march);
            f.createProduct(); f.useExistingPartner();
            MInOutLine receipt = f.movement(false, "20", "30", january);
            f.movement(true, "10", "50", Timestamp.valueOf(TEST_YEAR + "-01-02 00:00:00"));
            f.closePeriod(january);
            MLandedCostAllocation first = f.pendingLandedCost(receipt, "200", february);
            MLandedCostAllocation second = f.pendingLandedCost(receipt, "100", march);
            BulkProbe process = new BulkProbe(f, f.productId);
            process.from(february); process.through(Timestamp.valueOf(TEST_YEAR + "-03-31 00:00:00"));
            process.generateCostDetail();
            f.post(MInvoice.Table_ID, first.getInvoice().get_ID());
            f.post(MInvoice.Table_ID, second.getInvoice().get_ID());
            f.closePeriod(march);
            List<List<String>> costs = f.snapshotUnchecked("M_Cost");
            List<List<String>> details = f.snapshotUnchecked("M_CostDetail");
            List<List<String>> facts = f.snapshotUnchecked("Fact_Acct");
            MTransaction transaction = new Query(f.ctx, MTransaction.Table_Name,
                    "M_InOutLine_ID=?", f.name).setParameters(receipt.get_ID()).firstOnly();
            java.sql.Savepoint savepoint = f.transaction.getConnection().setSavepoint();
            IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                    CostEngineFactory.getCostEngine(CLIENT_ID).createCostDetail(f.schema,
                            (MCostType) f.schema.getM_CostType(), f.material, transaction, first, true));
            assertTrue(error.getMessage().contains("closed period"), error.getMessage());
            assertEquals(facts, f.snapshotUnchecked("Fact_Acct"), "Closed successor facts must stay untouched even before rollback");
            f.transaction.getConnection().rollback(savepoint);
            assertEquals(costs, f.snapshotUnchecked("M_Cost"));
            assertEquals(details, f.snapshotUnchecked("M_CostDetail"));
        }
    }

    @Test
    public void test34_FullySoldLandedCostKeepsAKnownProvisionalCostForTheNextSale() {
        try (Fixture f = new Fixture("TEST34")) {
            Timestamp january = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            Timestamp february = Timestamp.valueOf(TEST_YEAR + "-02-01 00:00:00");
            f.assertDocumentPreconditions(january); f.assertDocumentPreconditions(february);
            f.createProduct(); f.useExistingPartner();
            MInOutLine receipt = f.movement(false, "20", "30", january);
            f.movement(true, "20", "50", Timestamp.valueOf(TEST_YEAR + "-01-02 00:00:00"));
            f.closePeriod(january);
            f.pendingLandedCost(receipt, "200", february);
            BulkProbe process = new BulkProbe(f, f.productId);
            process.from(february); process.through(Timestamp.valueOf(TEST_YEAR + "-02-28 00:00:00"));
            process.generateCostDetail();
            f.assertCostState("0", "0", "30");
            MInOutLine next = f.movement(true, "1", "50", Timestamp.valueOf(TEST_YEAR + "-02-02 00:00:00"));
            f.assertCostState("-1", "-30", "30");
            f.post(MInOut.Table_ID, next.getM_InOut_ID());
        }
    }

    @Test
    public void test35_LateCostSurvivesRebuildingPhysicalMovementsInTheOpenPeriod() {
        try (Fixture f = new Fixture("TEST35")) {
            Timestamp january = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            Timestamp february = Timestamp.valueOf(TEST_YEAR + "-02-01 00:00:00");
            f.assertDocumentPreconditions(january); f.assertDocumentPreconditions(february);
            f.createProduct(); f.useExistingPartner();
            MInOutLine receipt = f.movement(false, "20", "30", january);
            MInOutLine januarySale = f.movement(true, "10", "50", Timestamp.valueOf(TEST_YEAR + "-01-02 00:00:00"));
            f.post(MInOut.Table_ID, receipt.getM_InOut_ID());
            f.post(MInOut.Table_ID, januarySale.getM_InOut_ID());
            List<List<String>> details = f.historicalRows("M_CostDetail", february);
            List<List<String>> facts = f.historicalRows("Fact_Acct", february);
            f.closePeriod(january);
            MLandedCostAllocation allocation = f.pendingLandedCost(receipt, "200", february);
            BulkProbe process = new BulkProbe(f, f.productId);
            process.from(february); process.through(Timestamp.valueOf(TEST_YEAR + "-02-28 00:00:00"));
            process.generateCostDetail();
            f.post(MInvoice.Table_ID, allocation.getInvoice().get_ID());
            MInOutLine februarySale = f.movement(true, "1", "50", Timestamp.valueOf(TEST_YEAR + "-02-02 00:00:00"));
            f.post(MInOut.Table_ID, februarySale.getM_InOut_ID());
            int count = f.count("M_CostDetail");
            for (int repetition = 0; repetition < 2; repetition++) {
                process.generateCostDetail();
                f.assertCostState("9", "360", "40");
                assertEquals(count, f.count("M_CostDetail"), "The old allocation must be rebuilt exactly once");
                f.post(MInvoice.Table_ID, allocation.getInvoice().get_ID());
                f.post(MInOut.Table_ID, februarySale.getM_InOut_ID());
                assertDecimal("360", f.accountBalance("P_Asset_Acct"), "Inventory after rebuilding February");
                assertDecimal("340", f.accountBalance("P_Cogs_Acct"), "COGS before and after rebuilding February");
                assertDecimal("100", f.accountBalance("P_CostAdjustment_Acct"), "The freight must not disappear or duplicate");
                f.assertHistoricalUnchanged("M_CostDetail", details, february);
                assertEquals(facts, f.historicalRows("Fact_Acct", february));
                assertTrue(new MInOut(f.ctx, receipt.getM_InOut_ID(), f.name).isPosted());
                assertTrue(new MInOut(f.ctx, januarySale.getM_InOut_ID(), f.name).isPosted());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"20,30,10,300,30,0", "20,20,10,200,20,-100",
            "5,40,-5,-150,30,50", "10,40,0,0,30,100"})
    public void test36_ClosedPeriodReplenishmentMatrix(String qty, String price,
            String remaining, String value, String average, String adjustment) {
        try (Fixture f = new Fixture("TEST36")) {
            Timestamp january = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            Timestamp february = Timestamp.valueOf(TEST_YEAR + "-02-01 00:00:00");
            f.assertDocumentPreconditions(january); f.assertDocumentPreconditions(february);
            f.createProduct(); f.useExistingPartner();
            MInOutLine initial = f.movement(false, "10", "30", january);
            f.post(MInOut.Table_ID, initial.getM_InOut_ID());
            MInOutLine sale = f.movement(true, "20", "50", Timestamp.valueOf(TEST_YEAR + "-01-02 00:00:00"));
            f.post(MInOut.Table_ID, sale.getM_InOut_ID());
            List<List<String>> oldFacts = f.historicalRows("Fact_Acct", february);
            List<List<String>> oldDetails = f.historicalRows("M_CostDetail", february);
            f.closePeriod(january);
            MInOutLine receipt = f.movement(false, qty, price, february);
            f.post(MInOut.Table_ID, receipt.getM_InOut_ID());
            MInvoiceLine invoice = f.invoice(receipt, qty, price, february);
            f.postInvoiceAndMatch(invoice, receipt);
            f.assertCostState(remaining, value, average);
            assertDecimal(value, f.accountBalance("P_Asset_Acct"), "Inventory agrees with the ledger");
            assertDecimal(adjustment, f.accountBalance("P_CostAdjustment_Acct"), "Only the covered deficit is adjusted");
            f.assertHistoricalUnchanged("Fact_Acct", oldFacts, february);
            f.assertHistoricalUnchanged("M_CostDetail", oldDetails, february);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "true,true"})
    public void test37_InvoiceAfterFurtherShipmentAllocatesConsumedStock(boolean closeBeforeInvoice, boolean extraReceipt) {
        try (Fixture f = new Fixture("TEST37")) {
            Timestamp day = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            f.assertDocumentPreconditions(day); f.createProduct(); f.useExistingPartner();
            MInOutLine initial = f.movement(false, "10", "30", day);
            f.post(MInOut.Table_ID, initial.getM_InOut_ID());
            MInOutLine firstSale = f.movement(true, "20", "50", Timestamp.valueOf(TEST_YEAR + "-01-02 00:00:00"));
            f.post(MInOut.Table_ID, firstSale.getM_InOut_ID());
            MInOutLine receipt = f.movement(false, "20", "40", Timestamp.valueOf(TEST_YEAR + "-01-03 00:00:00"));
            f.post(MInOut.Table_ID, receipt.getM_InOut_ID());
            if (extraReceipt) {
                MInOutLine extra = f.movement(false,"10","60",Timestamp.valueOf(TEST_YEAR + "-01-03 00:00:00"));
                f.post(MInOut.Table_ID,extra.getM_InOut_ID());
            }
            MInOutLine nextSale = f.movement(true, "4", "50", Timestamp.valueOf(TEST_YEAR + "-01-04 00:00:00"));
            f.post(MInOut.Table_ID, nextSale.getM_InOut_ID());
            Timestamp february = Timestamp.valueOf(TEST_YEAR + "-02-01 00:00:00");
            List<List<String>> oldFacts = f.historicalRows("Fact_Acct", february);
            List<List<String>> oldDetails = f.historicalRows("M_CostDetail", february);
            if (closeBeforeInvoice) { f.assertDocumentPreconditions(february); f.closePeriod(day); }
            MInvoiceLine invoice = f.invoice(receipt, "20", "50", closeBeforeInvoice ? february : Timestamp.valueOf(TEST_YEAR + "-01-05 00:00:00"));
            f.postInvoiceAndMatch(invoice, receipt);
            if (closeBeforeInvoice) {
                f.assertHistoricalUnchanged("Fact_Acct",oldFacts,february);
                f.assertHistoricalUnchanged("M_CostDetail",oldDetails,february);
            } else {
                // Open successor facts can be invalidated by a controlled replay.
                f.post(MInOut.Table_ID,nextSale.getM_InOut_ID());
            }
            f.assertCostState(extraReceipt ? "16" : "6", extraReceipt ? "880" : "300", extraReceipt ? "55" : "50");
            assertDecimal(extraReceipt ? "880" : "300", f.accountBalance("P_Asset_Acct"), "Remaining stock includes only its weighted share of the late price delta");
            assertDecimal(closeBeforeInvoice && !extraReceipt ? "760" : "800", f.accountBalance("P_Cogs_Acct"), "Closed postings are preserved; open successors are reposted at their revised cost");
            assertDecimal(extraReceipt ? "220" : closeBeforeInvoice ? "240" : "200", f.accountBalance("P_CostAdjustment_Acct"), "Closed consumption is adjusted in the open period");
        }
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    public void test38_InvoiceAfterWarehouseTransferPreservesClientQuantity(boolean closeBeforeInvoice) {
        try (Fixture f = new Fixture("TEST38")) {
            Timestamp day = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            f.assertDocumentPreconditions(day); f.createProduct(); f.useExistingPartner();
            MInOutLine initial = f.movement(false, "10", "30", day);
            f.post(MInOut.Table_ID, initial.getM_InOut_ID());
            MInOutLine sale = f.movement(true, "20", "50", Timestamp.valueOf(TEST_YEAR + "-01-02 00:00:00"));
            f.post(MInOut.Table_ID, sale.getM_InOut_ID());
            MInOutLine receipt = f.movement(false, "20", "40", Timestamp.valueOf(TEST_YEAR + "-01-03 00:00:00"));
            f.post(MInOut.Table_ID, receipt.getM_InOut_ID());
            MMovement move = new MMovement(f.ctx, 0, f.name);
            move.setAD_Org_ID(ORG_ID); move.setDescription(f.owner);
            move.setMovementDate(Timestamp.valueOf(TEST_YEAR + "-01-04 00:00:00"));
            move.saveEx();
            MMovementLine line = new MMovementLine(move);
            line.setM_Product_ID(f.productId);
            line.setM_Locator_ID(LOCATOR_ID); line.setM_LocatorTo_ID(102);
            line.setM_AttributeSetInstance_ID(new MInOutLine(f.ctx, receipt.get_ID(), f.name).getM_AttributeSetInstance_ID());
            line.setMovementQty(new BigDecimal("4")); line.saveEx();
            assertTrue(move.processIt(DocAction.ACTION_Complete), move.getProcessMsg()); move.saveEx();
            f.post(MMovement.Table_ID, move.get_ID());
            Timestamp february=Timestamp.valueOf(TEST_YEAR+"-02-01 00:00:00");
            List<List<String>> oldFacts=f.historicalRows("Fact_Acct",february);
            List<List<String>> oldDetails=f.historicalRows("M_CostDetail",february);
            if(closeBeforeInvoice){f.assertDocumentPreconditions(february);f.closePeriod(day);}
            MInvoiceLine invoice = f.invoice(receipt, "20", "50", closeBeforeInvoice?february:Timestamp.valueOf(TEST_YEAR + "-01-05 00:00:00"));
            f.postInvoiceAndMatch(invoice, receipt);
            if(closeBeforeInvoice){f.assertHistoricalUnchanged("Fact_Acct",oldFacts,february);f.assertHistoricalUnchanged("M_CostDetail",oldDetails,february);}
            f.assertCostState("10", "500", "50");
            assertDecimal("10", DB.getSQLValueBD(f.name,"SELECT SUM(QtyOnHand) FROM M_Storage WHERE M_Product_ID=?",f.productId),"Transfer is not consumption");
            assertDecimal("4", DB.getSQLValueBD(f.name,"SELECT SUM(QtyOnHand) FROM M_Storage WHERE M_Product_ID=? AND M_Locator_ID=102",f.productId),"Destination stock");
            assertDecimal("500", f.accountBalance("P_Asset_Acct"), "Global inventory after transfer and invoice");
            assertDecimal("200", f.accountBalance("P_CostAdjustment_Acct"), "Only the ten units initially covered are expensed");
        }
    }

    @Test
    public void test39_InvoiceKeepsReceiptAttributesAndPostsPriceDifference() {
        try (Fixture f = new Fixture("TEST39")) {
            Timestamp day = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            f.assertDocumentPreconditions(day); f.createProduct(); f.useExistingPartner();
            MInOutLine receipt = f.movement(false, "10", "10", day);
            f.post(MInOut.Table_ID, receipt.getM_InOut_ID());
            MInvoiceLine invoice = f.invoice(receipt,"10","12",Timestamp.valueOf(TEST_YEAR + "-01-02 00:00:00"),true);
            assertEquals(new MInOutLine(f.ctx,receipt.get_ID(),f.name).getM_AttributeSetInstance_ID(),invoice.getM_AttributeSetInstance_ID());
            f.postInvoiceAndMatch(invoice,receipt);
            f.assertCostState("10","120","12");
            assertDecimal("120",f.accountBalance("P_Asset_Acct"),"Invoice adjustment belongs to inventory");
            assertDecimal("0",f.accountBalance("P_CostAdjustment_Acct"),"Nothing has been consumed");
            int facts=f.count("Fact_Acct"), details=f.count("M_CostDetail");
            f.postInvoiceAndMatch(invoice,receipt);
            assertEquals(facts,f.count("Fact_Acct"),"Forced reposting must not duplicate accounting lines");
            assertEquals(details,f.count("M_CostDetail"),"Forced reposting must not duplicate cost events");
            assertDecimal("120",f.accountBalance("P_Asset_Acct"),"Forced reposting preserves inventory value");
        }
    }

    @Test
    public void test40_RealLandedCostInvoiceAndReversalRestoreClosedReceiptValue() {
        try (Fixture f = new Fixture("TEST40")) {
            Timestamp january=Timestamp.valueOf(TEST_YEAR+"-01-01 00:00:00");
            Timestamp february=Timestamp.valueOf(TEST_YEAR+"-02-01 00:00:00");
            f.assertDocumentPreconditions(january);f.assertDocumentPreconditions(february);
            f.createProduct();f.useExistingPartner();
            MInOutLine receipt=f.movement(false,"20","30",january);
            f.post(MInOut.Table_ID,receipt.getM_InOut_ID());
            MInOutLine sale=f.movement(true,"10","50",Timestamp.valueOf(TEST_YEAR+"-01-02 00:00:00"));
            f.post(MInOut.Table_ID,sale.getM_InOut_ID());
            List<List<String>> facts=f.historicalRows("Fact_Acct",february);
            List<List<String>> details=f.historicalRows("M_CostDetail",february);
            f.closePeriod(january);
            MInvoice invoice=f.realLandedCostInvoice(receipt,"200",february);
            f.post(MInvoice.Table_ID,invoice.get_ID());
            f.assertCostState("10","400","40");
            assertDecimal("400",f.accountBalance("P_Asset_Acct"),"Actual invoice wizard path capitalizes the remaining half");
            assertDecimal("100",f.accountBalance("P_CostAdjustment_Acct"),"Already sold half is expensed");
            assertTrue(invoice.reverseCorrectIt(),invoice.getProcessMsg());invoice.saveEx();
            f.post(MInvoice.Table_ID,invoice.getReversal_ID());
            f.assertCostState("10","300","30");
            assertDecimal("300",f.accountBalance("P_Asset_Acct"),"Reversal restores inventory");
            assertDecimal("0",f.accountBalance("P_CostAdjustment_Acct"),"Reversal compensates sold-unit adjustment");
            f.assertHistoricalUnchanged("Fact_Acct",facts,february);
            f.assertHistoricalUnchanged("M_CostDetail",details,february);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,10,10,200,20,100", "true,10,10,200,20,100",
            "false,5,15,300,20,50", "true,5,15,300,20,50"})
    public void test44_LateLandedCostDoesNotCapitalizeAReplacementReceipt(boolean reuseAttributes,
            String sold, String quantity, String value, String average, String expense) {
        try (Fixture f = new Fixture("TEST44")) {
            Timestamp january = Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00");
            Timestamp february = Timestamp.valueOf(TEST_YEAR + "-02-01 00:00:00");
            f.assertDocumentPreconditions(january);
            f.assertDocumentPreconditions(february);
            f.createProduct();
            f.useExistingPartner();
            MInOutLine original = f.movement(false, "10", "10", january);
            f.post(MInOut.Table_ID, original.getM_InOut_ID());
            MInOutLine sale = f.movement(true, sold, "50",
                    Timestamp.valueOf(TEST_YEAR + "-01-02 00:00:00"));
            f.post(MInOut.Table_ID, sale.getM_InOut_ID());
            f.assertCostState("10".equals(sold) ? "0" : "5", "10".equals(sold) ? "0" : "50", "10");
            int originalAsi = new MInOutLine(f.ctx, original.get_ID(), f.name).getM_AttributeSetInstance_ID();
            assertTrue(originalAsi > 0, "The receipt has an automatically assigned stock identity");
            MInOutLine replacement = f.draftMovement(false, "10", "20",
                    Timestamp.valueOf(TEST_YEAR + "-01-03 00:00:00"));
            if (reuseAttributes) {
                replacement.setM_AttributeSetInstance_ID(originalAsi);
                replacement.saveEx();
            }
            MInOut replacementDocument = replacement.getParent();
            assertTrue(replacementDocument.processIt(DocAction.ACTION_Complete), replacementDocument.getProcessMsg());
            replacementDocument.saveEx();
            int replacementAsi = new MInOutLine(f.ctx, replacement.get_ID(), f.name).getM_AttributeSetInstance_ID();
            assertEquals(reuseAttributes, originalAsi == replacementAsi,
                    "Exercise both automatic distinct identities and explicitly reused attributes");
            f.post(MInOut.Table_ID, replacement.getM_InOut_ID());
            f.assertCostState(quantity, "10".equals(sold) ? "200" : "250",
                    "10".equals(sold) ? "20" : "16.6667");
            List<List<String>> facts = f.historicalRows("Fact_Acct", february);
            List<List<String>> details = f.historicalRows("M_CostDetail", february);
            f.closePeriod(january);

            // The freight belongs only to the original receipt, including any retained share.
            MInvoice freight = f.realLandedCostInvoice(original, "100", february);
            MLandedCostAllocation allocation = new Query(f.ctx, MLandedCostAllocation.Table_Name,
                    "C_InvoiceLine_ID IN (SELECT C_InvoiceLine_ID FROM C_InvoiceLine WHERE C_Invoice_ID=?)", f.name)
                    .setParameters(freight.get_ID()).firstOnly();
            assertNotNull(allocation, "Invoice completion creates the real freight allocation");
            assertEquals(original.get_ID(), allocation.getM_InOutLine_ID());
            assertEquals(originalAsi, allocation.getM_AttributeSetInstance_ID());
            f.post(MInvoice.Table_ID, freight.get_ID());
            assertAll("Late freight after consumption and replacement",
                    () -> f.assertCostState(quantity, value, average),
                    () -> assertDecimal(value, f.accountBalance("P_Asset_Acct"),
                            "Only retained original-receipt freight belongs to inventory"),
                    () -> assertDecimal(expense, f.accountBalance("P_CostAdjustment_Acct"),
                            "Only consumed original-receipt freight belongs to expense"),
                    () -> f.assertHistoricalUnchanged("Fact_Acct", facts, february),
                    () -> f.assertHistoricalUnchanged("M_CostDetail", details, february));
        }
    }

    @Test
    public void test41_TwoSchemasCalculateAndPostTheCompleteNegativeStockChain() {
        try (SecondarySchema secondary=new SecondarySchema(); Fixture f=new Fixture("TEST41")) {
            Timestamp january=Timestamp.valueOf(TEST_YEAR+"-01-01 00:00:00");
            f.assertDocumentPreconditions(january);f.createProduct();f.useExistingPartner();
            MAcctSchema other=new MAcctSchema(f.ctx,secondary.id,f.name);
            MAcctSchema[] schemas={f.schema,other};
            MInOutLine initial=f.movement(false,"10","30",january);
            f.postAll(MInOut.Table_ID,initial.getM_InOut_ID(),schemas);
            MInOutLine sale=f.movement(true,"20","50",Timestamp.valueOf(TEST_YEAR+"-01-02 00:00:00"));
            f.postAll(MInOut.Table_ID,sale.getM_InOut_ID(),schemas);
            MInOutLine receipt=f.movement(false,"20","40",Timestamp.valueOf(TEST_YEAR+"-01-03 00:00:00"));
            f.postAll(MInOut.Table_ID,receipt.getM_InOut_ID(),schemas);
            MInvoiceLine invoice=f.invoice(receipt,"20","50",Timestamp.valueOf(TEST_YEAR+"-01-04 00:00:00"));
            f.postAll(MInvoice.Table_ID,invoice.getC_Invoice_ID(),schemas);
            MMatchInv match=new Query(f.ctx,MMatchInv.Table_Name,"C_InvoiceLine_ID=?",f.name).setParameters(invoice.get_ID()).firstOnly();
            assertNotNull(match);f.postAll(MMatchInv.Table_ID,match.get_ID(),schemas);
            for(MAcctSchema schema:schemas) {
                MCost cost=new Query(f.ctx,MCost.Table_Name,"AD_Client_ID=? AND M_Product_ID=? AND C_AcctSchema_ID=? AND M_CostType_ID=? AND M_CostElement_ID=? AND AD_Org_ID=0 AND M_Warehouse_ID=0 AND M_AttributeSetInstance_ID=0",f.name)
                        .setParameters(CLIENT_ID,f.productId,schema.get_ID(),schema.getM_CostType_ID(),f.material.get_ID()).firstOnly();
                assertNotNull(cost);assertDecimal("10",cost.getCurrentQty(),"Quantity must not be summed across schemas");
                assertDecimal("500",cost.getCumulatedAmt(),"Independent schema inventory");
                assertDecimal("500",f.accountBalance("P_Asset_Acct",schema.get_ID()),"Independent inventory posting");
                assertDecimal("200",f.accountBalance("P_CostAdjustment_Acct",schema.get_ID()),"Independent expense adjustment");
            }
            int otherDetails=new Query(f.ctx,MCostDetail.Table_Name,"M_Product_ID=? AND C_AcctSchema_ID=?",f.name).setParameters(f.productId,secondary.id).count();
            new BulkProbe(f,f.productId).generateCostDetail();
            assertEquals(otherDetails,new Query(f.ctx,MCostDetail.Table_Name,"M_Product_ID=? AND C_AcctSchema_ID=?",f.name).setParameters(f.productId,secondary.id).count(),"Rebuilding one schema must preserve the other");
            // Posted is a document-level flag. Repost the affected open documents
            // for both schemas after a one-schema rebuild and check both ledgers.
            for(int id:new int[]{initial.getM_InOut_ID(),sale.getM_InOut_ID(),receipt.getM_InOut_ID()})f.postAll(MInOut.Table_ID,id,schemas);
            f.postAll(MInvoice.Table_ID,invoice.getC_Invoice_ID(),schemas);f.postAll(MMatchInv.Table_ID,match.get_ID(),schemas);
            for(MAcctSchema schema:schemas){
                assertDecimal("500",f.accountBalance("P_Asset_Acct",schema.get_ID()),"Both ledgers reconcile after regeneration and reposting");
                assertDecimal("200",f.accountBalance("P_CostAdjustment_Acct",schema.get_ID()),"No schema receives a duplicate expense adjustment");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"0.00004,0,true", "0.00005,0,false", "0.00004,0.00004,false", "-0.00005,0,false"})
    public void test43_CostPrecisionBoundary(String amount,String lower,boolean rejected) {
        try(Fixture f=new Fixture("TEST43")) {
            f.createProduct();assertEquals(4,f.schema.getCostingPrecision(),"Fixture requires the documented USD precision");
            MCostDetail component=new MCostDetail(f.ctx,0,f.name);
            component.setCostAmt(new BigDecimal(amount));component.setCostAmtLL(new BigDecimal(lower));
            Runnable check=()->CostEngine.requireSalesCost(f.schema,f.productId,0,f.schema.getM_CostType_ID(),Timestamp.valueOf(TEST_YEAR+"-01-01 00:00:00"),Arrays.asList(component));
            if(rejected)assertThrows(ZeroSalesCostException.class,check::run);else assertDoesNotThrow(check::run);
        }
    }

    /** Complete secondary accounting setup, owned by this test on the disposable clone. */
    private static final class SecondarySchema implements AutoCloseable {
        final Properties ctx=Env.getCtx(),original=new Properties();
        final List<PO> owned=new ArrayList<>();
        final Map<Integer,Integer> combinations=new LinkedHashMap<>();
        int id;
        SecondarySchema() {
            original.putAll(ctx);
            Env.setContext(ctx,"#AD_Client_ID",CLIENT_ID);Env.setContext(ctx,"#AD_Org_ID",ORG_ID);Env.setContext(ctx,"#AD_User_ID",CommonGWData.AD_USER_ID);
            try {
                Trx.run(tx->{
                    MAcctSchema source=new MAcctSchema(ctx,SCHEMA_ID,tx),schema=new MAcctSchema(ctx,0,tx);
                    PO.copyValues(source,schema);schema.setName("4411-schema-"+UUID.randomUUID());schema.saveEx();id=schema.get_ID();owned.add(schema);
                    for(String table:new String[]{"C_AcctSchema_Element","C_AcctSchema_GL","C_AcctSchema_Default","M_Product_Category_Acct","C_BP_Group_Acct","C_BP_Vendor_Acct","C_BP_Customer_Acct","C_Tax_Acct"}) {
                        for(PO row:new Query(ctx,table,"C_AcctSchema_ID=?",tx).setParameters(SCHEMA_ID).list()) {
                            PO copy=MTable.get(ctx,table).getPO(0,tx);PO.copyValues(row,copy);
                            if(row.get_KeyColumns().length>1)for(String key:row.get_KeyColumns())
                                if(!key.equals("C_AcctSchema_ID"))copy.set_ValueOfColumn(key,row.get_Value(key));
                            copy.set_ValueOfColumn("C_AcctSchema_ID",id);
                            for(int i=0;i<copy.get_ColumnCount();i++) {
                                String column=copy.get_ColumnName(i);
                                if(!column.endsWith("_Acct"))continue;
                                int old=row.get_ValueAsInt(column);if(old<=0)continue;
                                Integer mapped=combinations.get(old);
                                if(mapped==null) {
                                    MAccount account=new MAccount(ctx,0,tx);PO.copyValues(new MAccount(ctx,old,tx),account);
                                    account.setC_AcctSchema_ID(id);account.saveEx();owned.add(account);mapped=account.get_ID();combinations.put(old,mapped);
                                }
                                copy.set_ValueOfColumn(column,mapped);
                            }
                            copy.saveEx();owned.add(copy);
                        }
                    }
                });
                org.compiere.util.CacheMgt.get().reset("AD_ClientInfo");org.compiere.util.CacheMgt.get().reset("C_AcctSchema");
            }catch(RuntimeException|Error e){ctx.clear();ctx.putAll(original);throw e;}
        }
        public void close() {
            try {
                Trx.run(tx->{for(int i=owned.size()-1;i>=0;i--) {
                    PO row=owned.get(i);row.set_TrxName(tx);
                    if(row instanceof MAcctSchemaElement) {
                        assertEquals(id,row.get_ValueAsInt("C_AcctSchema_ID"));
                        assertEquals(CLIENT_ID,row.getAD_Client_ID());
                        assertEquals(1,DB.executeUpdateEx("DELETE FROM C_AcctSchema_Element WHERE C_AcctSchema_Element_ID=? AND C_AcctSchema_ID=? AND AD_Client_ID=?",new Object[]{row.get_ID(),id,CLIENT_ID},tx));
                    }else row.deleteEx(true);
                }});
            }finally {
                org.compiere.util.CacheMgt.get().reset("AD_ClientInfo");org.compiere.util.CacheMgt.get().reset("C_AcctSchema");org.compiere.util.CacheMgt.get().reset("C_AcctSchema_Element");ctx.clear();ctx.putAll(original);
            }
        }
    }

    @Test
    public void test42_PublicProcessOwnsTransactionAndRollsBackSecondProductFailure() throws Exception {
        Properties ctx=Env.getCtx(),saved=new Properties();saved.putAll(ctx);
        Env.setContext(ctx,"#AD_Client_ID",CLIENT_ID);Env.setContext(ctx,"#AD_Org_ID",ORG_ID);
        Env.setContext(ctx,"#AD_User_ID",CommonGWData.AD_USER_ID);Env.setContext(ctx,"#M_Warehouse_ID",WAREHOUSE_ID);
        Env.setContext(ctx,"#Date",TEST_YEAR+"-01-01 00:00:00");
        List<Integer> instances=new ArrayList<>();Map<Integer,Integer> oldCategories=new LinkedHashMap<>();
        final int[] category={0};
        try {
            try(Connection c=DB.getConnectionRW()){DatabaseIdentity.configured().verify(c);}
            List<MProduct> products=new Query(ctx,MProduct.Table_Name,"AD_Client_ID=? AND Value IN ('GW-AIC-READY','GW-AIC-AVG')",null)
                    .setParameters(CLIENT_ID).setOrderBy("M_Product_ID").list();
            assertEquals(2,products.size(),"Install XML 10355 on the disposable clone before the public-process test");
            for(MProduct p:products)oldCategories.put(p.get_ID(),p.getM_Product_Category_ID());
            Trx.run(tx->{
                MProductCategory group=new MProductCategory(ctx,0,tx);group.setName("4411-process-"+UUID.randomUUID());group.saveEx();category[0]=group.get_ID();
                for(MProduct p:products){p.set_TrxName(tx);p.setM_Product_Category_ID(category[0]);p.saveEx();}
            });
            Map<String,String> before=businessDigests();
            FailingGenerator failure=new FailingGenerator(products.get(1).get_ID());
            org.compiere.process.ProcessInfo failed=runPublicGenerator(ctx,category[0],failure,instances);
            assertTrue(failed.isError());assertTrue(failed.getSummary().contains("Cost regeneration failed"),failed.getSummary());
            assertEquals(2,failure.visited.size(),"Failure must follow a completed first product");
            assertEquals(before,businessDigests(),"The framework-owned transaction must restore all product/accounting rows");
            for(int run=0;run<2;run++) {
                org.compiere.process.ProcessInfo result=runPublicGenerator(ctx,category[0],new GenerateCostDetail(),instances);
                assertFalse(result.isError(),result.getSummary());
                Trx.run(tx->{
                    MAcctSchema schema=new MAcctSchema(ctx,SCHEMA_ID,tx);
                    for(String table:new String[]{"M_InOut","C_Invoice","M_MatchInv"}) {
                        String where=table.equals("M_InOut") ? "M_InOut_ID IN(SELECT DISTINCT t.M_InOut_ID FROM M_InOutLine t JOIN M_Product p ON p.M_Product_ID=t.M_Product_ID WHERE p.M_Product_Category_ID=?)"
                                : table.equals("C_Invoice") ? "C_Invoice_ID IN(SELECT DISTINCT t.C_Invoice_ID FROM C_InvoiceLine t JOIN M_Product p ON p.M_Product_ID=t.M_Product_ID WHERE p.M_Product_Category_ID=?)"
                                : "M_Product_ID IN(SELECT M_Product_ID FROM M_Product WHERE M_Product_Category_ID=?)";
                        for(PO document:new Query(ctx,table,where,tx).setParameters(category[0]).list())
                            assertNull(Doc.postImmediate(new MAcctSchema[]{schema},document.get_Table_ID(),document.get_ID(),true,tx),"Repost generated documents");
                    }
                });
                for(MProduct p:products) {
                    MCost cost=new Query(ctx,MCost.Table_Name,"M_Product_ID=? AND C_AcctSchema_ID=101 AND M_CostType_ID=50000 AND M_CostElement_ID=100 AND AD_Org_ID=0 AND M_Warehouse_ID=0 AND M_AttributeSetInstance_ID=0",null).setParameters(p.get_ID()).firstOnly();
                    assertNotNull(cost);boolean average=p.getValue().equals("GW-AIC-AVG");
                    assertDecimal(average?"16":"10",cost.getCurrentQty(),"Public process quantity");
                    assertDecimal(average?"240":"100",cost.getCumulatedAmt(),"Public process valuation");
                    assertDecimal(average?"15":"10",cost.getCurrentCostPrice(),"Public process average");
                    assertDecimal(average?"240":"100",DB.getSQLValueBDEx(null,"SELECT COALESCE(SUM(f.AmtAcctDr-f.AmtAcctCr),0) FROM Fact_Acct f WHERE f.M_Product_ID=? AND f.C_AcctSchema_ID=101 AND f.Account_ID=(SELECT vc.Account_ID FROM M_Product_Acct a JOIN C_ValidCombination vc ON vc.C_ValidCombination_ID=a.P_Asset_Acct WHERE a.M_Product_ID=? AND a.C_AcctSchema_ID=101)",p.get_ID(),p.get_ID()),"Public process inventory ledger");
                }
            }
        }finally {
            if(category[0]>0)Trx.run(tx->{
                for(Map.Entry<Integer,Integer> item:oldCategories.entrySet()){MProduct p=new MProduct(ctx,item.getKey(),tx);p.setM_Product_Category_ID(item.getValue());p.saveEx();}
                DB.executeUpdateEx("DELETE FROM M_Product_Category_Acct WHERE M_Product_Category_ID=? AND AD_Client_ID=?",new Object[]{category[0],CLIENT_ID},tx);
                new MProductCategory(ctx,category[0],tx).deleteEx(true);
            });
            for(int id:instances) {
                DB.executeUpdateEx("DELETE FROM AD_PInstance_Log WHERE AD_PInstance_ID=?",new Object[]{id},null);
                DB.executeUpdateEx("DELETE FROM AD_PInstance_Para WHERE AD_PInstance_ID=?",new Object[]{id},null);
                DB.executeUpdateEx("DELETE FROM AD_PInstance WHERE AD_PInstance_ID=? AND AD_Client_ID=? AND AD_Process_ID=?",new Object[]{id,CLIENT_ID,53223},null);
            }
            ctx.clear();ctx.putAll(saved);
        }
    }

    private static class FailingGenerator extends GenerateCostDetail {
        final int failOn;final java.util.Set<Integer> visited=new java.util.HashSet<>();
        FailingGenerator(int product){failOn=product;}
        @Override public void generateCostDetail(MAcctSchema schema,MCostType type,MCostElement element,MTransaction t) {
            super.generateCostDetail(schema,type,element,t);visited.add(t.getM_Product_ID());
            if(t.getM_Product_ID()==failOn)throw new IllegalStateException("Injected public-process second-product failure");
        }
    }

    private static org.compiere.process.ProcessInfo runPublicGenerator(Properties ctx,int category,
            GenerateCostDetail process,List<Integer> instances) {
        MPInstance instance=new MPInstance(ctx,0,null);instance.setAD_Process_ID(53223);
        instance.setAD_User_ID(CommonGWData.AD_USER_ID);instance.setRecord_ID(0);instance.saveEx();instances.add(instance.get_ID());
        org.compiere.process.ProcessInfo pi=new org.compiere.process.ProcessInfo("4411-public-process",53223);
        pi.setAD_PInstance_ID(instance.get_ID());pi.setAD_Client_ID(CLIENT_ID);pi.setAD_User_ID(CommonGWData.AD_USER_ID);
        pi.setParameter(new org.compiere.process.ProcessInfoParameter[]{
            new org.compiere.process.ProcessInfoParameter("C_AcctSchema_ID",new BigDecimal(SCHEMA_ID),null,null,null),
            new org.compiere.process.ProcessInfoParameter("M_CostType_ID",new BigDecimal(50000),null,null,null),
            new org.compiere.process.ProcessInfoParameter("M_CostElement_ID",new BigDecimal(100),null,null,null),
            new org.compiere.process.ProcessInfoParameter("M_Product_Category_ID",new BigDecimal(category),null,null,null),
            new org.compiere.process.ProcessInfoParameter("DateAcct",Timestamp.valueOf(TEST_YEAR+"-01-01 00:00:00"),Timestamp.valueOf(TEST_YEAR+"-01-31 00:00:00"),null,null)
        });
        boolean success=process.startProcess(ctx,pi,null);
        assertEquals(!pi.isError(),success,"Framework return value must reflect process status");
        assertFalse(new MPInstance(ctx,instance.get_ID(),null).isProcessing(),"The process instance must be unlocked");
        return pi;
    }

    private static Map<String,String> businessDigests() throws Exception {
        Map<String,String> result=new LinkedHashMap<>();
        try(Connection c=DB.getConnectionRW()) {
            DatabaseIdentity.configured().verify(c);
            for(String table:new String[]{"M_Cost","M_CostDetail","Fact_Acct","M_InOut","C_Invoice","M_MatchInv","M_Transaction","M_Storage"}) {
                List<String> rows=new ArrayList<>();
                try(PreparedStatement q=c.prepareStatement("SELECT row_to_json(t)::text FROM "+table+" t WHERE AD_Client_ID=?")) {
                    q.setInt(1,CLIENT_ID);try(ResultSet r=q.executeQuery()){while(r.next())rows.add(r.getString(1));}
                }
                java.util.Collections.sort(rows);
                byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(String.join("\n",rows).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                result.put(table,rows.size()+":"+java.util.Base64.getEncoder().encodeToString(digest));
            }
        }
        return result;
    }

    private static class BulkProbe extends GenerateCostDetail {
        final Fixture fixture;
        int failOnProduct;
        final java.util.Set<Integer> visitedProducts = new java.util.HashSet<>();
        BulkProbe(Fixture fixture, int productId) {
            this.fixture = fixture;
            setAcctSchemaId(SCHEMA_ID);
            setCostTypeId(fixture.schema.getM_CostType_ID());
            setCostElementId(fixture.material.get_ID());
            setProductId(productId);
            setProductCategoryId(fixture.ownedCategoryId);
            setDateAcct(Timestamp.valueOf(TEST_YEAR + "-01-01 00:00:00"));
            setDateAcctTo(Timestamp.valueOf(TEST_YEAR + "-01-31 00:00:00"));
        }
        void from(Timestamp start) { setDateAcct(start); }
        void through(Timestamp end) { setDateAcctTo(end); }
        void usingType(int typeId) { setCostTypeId(typeId); }
        @Override public Properties getCtx() { return fixture.ctx; }
        @Override public String get_TrxName() { return fixture.name; }
        @Override protected int getAD_Client_ID() { return CLIENT_ID; }
        @Override public void generateCostDetail(MAcctSchema schema, MCostType type,
                MCostElement element, MTransaction transaction) {
            super.generateCostDetail(schema, type, element, transaction);
            visitedProducts.add(transaction.getM_Product_ID());
            if (transaction.getM_Product_ID() == failOnProduct)
                throw new IllegalStateException("Injected second-product failure");
        }
    }

    private static void assertDecimal(String expected, BigDecimal actual, String message) {
        assertNotNull(actual, message);
        assertEquals(new BigDecimal(expected).stripTrailingZeros(), actual.stripTrailingZeros(), message);
    }

    private static int assertMovement(Fixture f, MInOutLine line, String type,
            String quantity, Timestamp day) {
        List<MTransaction> rows = new Query(f.ctx, MTransaction.Table_Name,
                "M_Product_ID=? AND M_InOutLine_ID=?", f.name)
                .setParameters(f.productId, line.get_ID()).list();
        assertEquals(1, rows.size(), "One inventory transaction per owned document line");
        MTransaction row = rows.get(0);
        f.assertOwned(row);
        assertEquals(type, row.getMovementType());
        assertEquals(day, row.getMovementDate());
        assertEquals(LOCATOR_ID, row.getM_Locator_ID());
        assertDecimal(quantity, row.getMovementQty(), "Signed inventory movement");
        return row.get_ID();
    }

    /** Owns every business write. Legacy static lookups require a restored global context. */
    private static final class Fixture implements AutoCloseable {
        final Properties ctx = new Properties();
        final Properties originalContext = new Properties();
        final Map<MPeriodControl, String> originalPeriodStatus = new LinkedHashMap<>();
        final Map<String, List<String>> historicalColumnNames = new LinkedHashMap<>();
        final String owner;
        final String name;
        final Trx transaction;
        int productId;
        int ownedCategoryId;
        MProduct product;
        MAcctSchema schema;
        MCostElement material;
        MPriceList purchasePriceList;
        MBPartner partner;
        MBPartnerLocation partnerLocation;
        int partnerId;
        boolean ownsPartner;
        int locationId;
        int documentSequence;
        final Map<String, List<List<String>>> sharedBefore = new LinkedHashMap<>();

        Fixture(String test) {
            DatabaseIdentity.configured();
            owner = "4411-" + UUID.randomUUID().toString().replace("-", "");
            ctx.putAll(Env.getCtx());
            Env.setContext(ctx, "#AD_Client_ID", CLIENT_ID);
            Env.setContext(ctx, "#AD_Org_ID", ORG_ID);
            Env.setContext(ctx, "#AD_User_ID", CommonGWData.AD_USER_ID);
            Env.setContext(ctx, "#M_Warehouse_ID", WAREHOUSE_ID);
            Env.setContext(ctx, "$C_Currency_ID", CommonGWData.USD_CURRENCY_ID);
            Env.setContext(ctx, "#C_Currency_ID", CommonGWData.USD_CURRENCY_ID);
            Env.setContext(ctx, "#Date", TEST_YEAR + "-01-01 00:00:00");
            Env.setContext(ctx, "#COST_DEBUG", "N");
            // MDocType.getDocType and other legacy helpers consult Env.getCtx().
            // Run in a dedicated JVM and restore all keys, including on failure.
            originalContext.putAll(Env.getCtx());
            Env.getCtx().putAll(ctx);
            name = Trx.createTrxName(test + "-" + owner);
            transaction = Trx.get(name, true);
            try {
                DatabaseIdentity.configured().verify(transaction.getConnection());
                // Full rows, including audit columns, detect unintended out-of-transaction writes.
                for (String table : new String[] {"C_BPartner", "M_Cost", "C_DocType",
                        "C_AcctSchema", "M_Warehouse", "M_Locator", "M_PriceList",
                        "M_Product", "C_Period", "C_PeriodControl", "C_Tax", "M_Product_Category", "M_Product_Category_Acct"}) {
                    sharedBefore.put(table, snapshot(table, name));
                }
            } catch (SQLException | RuntimeException | Error failure) {
                try { rollbackAndClose(transaction); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                restoreContext();
                throw new IllegalStateException("Fixture initialization failed", failure);
            }
        }

        void assertDocumentPreconditions(Timestamp day) {
            assertEquals(CostEngine.class, CostEngineFactory.getCostEngine(CLIENT_ID).getClass(),
                    "TEST02 requires the audited default cost engine");
            MAcctSchema primary = MClient.get(ctx, CLIENT_ID).getAcctSchema();
            assertNotNull(primary, "Missing client primary accounting schema");
            // MPeriod.isOpen can save the cached schema outside our transaction when
            // automatic control is enabled. Refuse that path; never toggle shared setup.
            assertFalse(primary.isAutoPeriodControl(),
                    "TEST02 requires preconfigured manual period control on the disposable clone");
            for (String base : new String[] {MDocType.DOCBASETYPE_MaterialDelivery,
                    MDocType.DOCBASETYPE_MaterialReceipt, MDocType.DOCBASETYPE_APInvoice,
                    MDocType.DOCBASETYPE_MatchInvoice}) {
                assertTrue(MPeriod.isOpen(ctx, day, base, ORG_ID, name),
                        "Fixture accounting period must already be open for " + base);
            }
            MLocator locator = new MLocator(ctx, LOCATOR_ID, name);
            assertEquals(LOCATOR_ID, locator.get_ID(), "Missing fixture locator");
            assertEquals(CLIENT_ID, locator.getAD_Client_ID());
            assertEquals(ORG_ID, locator.getAD_Org_ID());
            assertEquals(WAREHOUSE_ID, locator.getM_Warehouse_ID());
            assertTrue(locator.isActive());
            purchasePriceList = new Query(ctx, MPriceList.Table_Name,
                    "AD_Client_ID=? AND IsSOPriceList='N' AND C_Currency_ID=?", name)
                    .setParameters(CLIENT_ID, CommonGWData.USD_CURRENCY_ID)
                    .setOnlyActiveRecords(true).setOrderBy("M_PriceList_ID").first();
            assertNotNull(purchasePriceList, "Fixture requires an active USD purchase price list");
        }

        void createPartner() {
            // Read only configuration; never process documents against an existing partner.
            MBPartner template = new MBPartner(ctx, CommonGWData.JOE_BLOCK_ID, name);
            assertEquals(CommonGWData.JOE_BLOCK_ID, template.get_ID());
            assertEquals(CLIENT_ID, template.getAD_Client_ID());
            MBPartnerLocation source = new MBPartnerLocation(ctx,
                    CommonGWData.JOE_BLOCK_LOCATION_ID, name);
            assertEquals(template.get_ID(), source.getC_BPartner_ID());
            MLocation address = new MLocation(ctx, source.getC_Location_ID(), name);
            assertEquals(source.getC_Location_ID(), address.get_ID());
            assertTrue(address.get_ID() > 0);
            MLocation ownedAddress = new MLocation(ctx, 0, name);
            ownedAddress.setAD_Org_ID(ORG_ID);
            ownedAddress.setC_Country_ID(address.getC_Country_ID());
            ownedAddress.setC_Region_ID(address.getC_Region_ID());
            ownedAddress.setCity(address.getCity());
            ownedAddress.setC_City_ID(address.getC_City_ID());
            ownedAddress.setPostal(address.getPostal());
            ownedAddress.setAddress1(owner);
            ownedAddress.saveEx();
            locationId = ownedAddress.get_ID();
            assertOwned(ownedAddress);

            partner = new MBPartner(ctx, 0, name);
            partner.setAD_Org_ID(ORG_ID);
            partner.setValue(owner);
            partner.setName(owner);
            partner.setIsVendor(true);
            partner.setIsCustomer(true);
            partner.setIsProspect(false);
            partner.setC_BP_Group_ID(template.getC_BP_Group_ID());
            partner.saveEx();
            partnerId = partner.get_ID();
            ownsPartner = true;
            assertOwned(partner);
            partnerLocation = new MBPartnerLocation(partner);
            partnerLocation.setName(owner);
            partnerLocation.setC_Location_ID(locationId);
            partnerLocation.saveEx();
            assertOwned(partnerLocation);
        }

        void useExistingPartner() {
            partner = new MBPartner(ctx, CommonGWData.JOE_BLOCK_ID, name);
            partnerId = partner.get_ID();
            assertEquals(CommonGWData.JOE_BLOCK_ID, partnerId);
            partnerLocation = new MBPartnerLocation(ctx, CommonGWData.JOE_BLOCK_LOCATION_ID, name);
            assertEquals(partnerId, partnerLocation.getC_BPartner_ID());
        }

        void closePeriod(Timestamp day) {
            MPeriod period = MPeriod.get(ctx, day, ORG_ID, name);
            assertNotNull(period);
            for (MPeriodControl control : period.getPeriodControls(false)) {
                originalPeriodStatus.put(control, control.getPeriodStatus());
                control.setPeriodStatus(MPeriodControl.PERIODSTATUS_Closed);
                control.saveEx(name);
            }
        }

        List<List<String>> historicalRows(String table, Timestamp cutoff) {
            if (!Arrays.asList("Fact_Acct", "M_CostDetail").contains(table))
                throw new IllegalArgumentException("Unsupported historical snapshot");
            List<List<String>> rows = new ArrayList<>();
            try (PreparedStatement statement = transaction.getConnection().prepareStatement(
                    "SELECT * FROM " + table + " WHERE M_Product_ID=? AND DateAcct<? ORDER BY " + table + "_ID")) {
                statement.setInt(1, productId);
                statement.setTimestamp(2, cutoff);
                try (ResultSet result = statement.executeQuery()) {
                    List<String> names = new ArrayList<>();
                    for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) names.add(result.getMetaData().getColumnName(i));
                    historicalColumnNames.put(table, names);
                    while (result.next()) {
                        List<String> row = new ArrayList<>();
                        for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) row.add(result.getString(i));
                        rows.add(row);
                    }
                }
            } catch (SQLException e) { throw new IllegalStateException(e); }
            assertFalse(rows.isEmpty(), "Historical snapshot must contain the original posting/costs");
            return rows;
        }

        void assertHistoricalUnchanged(String table, List<List<String>> expected, Timestamp cutoff) {
            List<List<String>> actual = historicalRows(table, cutoff);
            assertEquals(expected.size(), actual.size(), "Historical row count: " + table);
            for (int row = 0; row < expected.size(); row++)
                for (int column = 0; column < expected.get(row).size(); column++)
                    assertEquals(expected.get(row).get(column), actual.get(row).get(column),
                            "Closed " + table + " row=" + expected.get(row).get(0)
                            + " costType=" + (table.equals("M_CostDetail") ? expected.get(row).get(historicalColumnNames.get(table).indexOf("m_costtype_id")) : "n/a")
                            + " column=" + historicalColumnNames.get(table).get(column));
        }

        void post(int table, int record) { postAll(table,record,new MAcctSchema[]{schema}); }
        void postAll(int table,int record,MAcctSchema[] schemas) {
            assertNull(Doc.postImmediate(schemas,table,record,true,name),"Posting must succeed");
            for(MAcctSchema current:schemas) {
                assertTrue(DB.getSQLValueEx(name,"SELECT COUNT(*) FROM Fact_Acct WHERE C_AcctSchema_ID=? AND AD_Table_ID=? AND Record_ID=?",current.get_ID(),table,record)>0,"Posting must create facts for each schema");
                assertDecimal("0",DB.getSQLValueBDEx(name,"SELECT COALESCE(SUM(AmtAcctDr-AmtAcctCr),0) FROM Fact_Acct WHERE C_AcctSchema_ID=? AND AD_Table_ID=? AND Record_ID=?",current.get_ID(),table,record),"Every schema must balance");
            }
        }

        BigDecimal accountBalance(String column) {return accountBalance(column,SCHEMA_ID);}
        BigDecimal accountBalance(String column,int schemaId) {
            if (!Arrays.asList("P_Asset_Acct", "P_Cogs_Acct", "P_CostAdjustment_Acct").contains(column))
                throw new IllegalArgumentException("Unsupported fixture account");
            return DB.getSQLValueBDEx(name, "SELECT COALESCE(SUM(f.AmtAcctDr-f.AmtAcctCr),0)"
                    + " FROM Fact_Acct f WHERE f.C_AcctSchema_ID=? AND f.Account_ID=("
                    + "SELECT vc.Account_ID FROM M_Product_Acct pa JOIN C_ValidCombination vc ON vc.C_ValidCombination_ID=pa."
                    + column + " WHERE pa.M_Product_ID=? AND pa.C_AcctSchema_ID=?)"
                    + " AND (f.M_Product_ID=? OR EXISTS (SELECT 1 FROM M_MatchInv mi"
                    + " WHERE f.AD_Table_ID=? AND mi.M_MatchInv_ID=f.Record_ID AND mi.M_Product_ID=?))",
                    schemaId, productId, schemaId, productId, MMatchInv.Table_ID, productId);
        }

        void assertOwned(PO row) {
            assertEquals(name, row.get_TrxName(), "Fixture transaction: " + row.get_TableName());
            assertEquals(CLIENT_ID, row.getAD_Client_ID(), "Fixture client");
            assertTrue(row.getAD_Org_ID() == 0 || row.getAD_Org_ID() == ORG_ID, "Fixture organization");
            if (row.get_ColumnIndex("M_Product_ID") >= 0) {
                assertEquals(productId, row.get_ValueAsInt("M_Product_ID"), "Owned product link");
            }
            if (row.get_ColumnIndex("C_BPartner_ID") >= 0) {
                assertEquals(partnerId, row.get_ValueAsInt("C_BPartner_ID"), "Owned partner link");
            }
        }

        void deleteOwnedDimension() {
            assertTrue(productId > 0);
            assertOwned(product);
            assertEquals(owner, product.getValue());
            assertEquals(1, new Query(ctx, MProduct.Table_Name,
                    "M_Product_ID=? AND AD_Client_ID=? AND Value=?", name)
                    .setParameters(productId, CLIENT_ID, owner).count(), "Deletion requires owned product");
            Object[] dimension = dimensionParameters();
            Object[] parameters = java.util.Arrays.copyOf(dimension, dimension.length + 1);
            parameters[dimension.length] = owner;
            assertEquals(1, DB.executeUpdateEx("DELETE FROM M_Cost WHERE " + DIMENSION
                    + " AND EXISTS (SELECT 1 FROM M_Product p WHERE p.M_Product_ID=M_Cost.M_Product_ID"
                    + " AND p.AD_Client_ID=M_Cost.AD_Client_ID AND p.Value=?)", parameters, name));
        }

        void createProduct() { createProduct(""); }

        void createProduct(String suffix) {
            MProduct template = new MProduct(ctx, TEMPLATE_PRODUCT_ID, name);
            assertEquals(TEMPLATE_PRODUCT_ID, template.get_ID(), "Missing GardenWorld template");
            assertEquals(CLIENT_ID, template.getAD_Client_ID());
            assertTrue(template.isActive(), "Inactive product template");
            schema = new MAcctSchema(ctx, SCHEMA_ID, name);
            assertEquals(SCHEMA_ID, schema.get_ID(), "Missing accounting schema");
            assertEquals(CLIENT_ID, schema.getAD_Client_ID());
            assertTrue(schema.isActive());
            MCostType type = new MCostType(ctx, schema.getM_CostType_ID(), name);
            assertTrue(type.get_ID() > 0 && type.isActive(), "Missing active cost type");
            assertEquals(CLIENT_ID, type.getAD_Client_ID());
            assertEquals(MCostType.COSTINGMETHOD_AverageInvoice, type.getCostingMethod());
            material = new Query(ctx, MCostElement.Table_Name,
                    "AD_Client_ID=? AND CostElementType=? AND IsDefault='Y'", name)
                    .setParameters(CLIENT_ID, MCostElement.COSTELEMENTTYPE_Material)
                    .setOnlyActiveRecords(true).firstOnly();
            assertNotNull(material, "Exactly one active default Material element is required");
            assertEquals(1, new Query(ctx, MCostElement.Table_Name,
                    "AD_Client_ID=? AND CostElementType=?", name)
                    .setParameters(CLIENT_ID, MCostElement.COSTELEMENTTYPE_Material)
                    .setOnlyActiveRecords(true).count(),
                    "Fixture requires one active Material element to avoid duplicate material valuation");
            assertEquals(MAcctSchema.COSTINGLEVEL_Client, template.getCostingLevel(schema),
                    "Fixture requires client-level costing");

            product = new MProduct(ctx, 0, name);
            product.setAD_Org_ID(0);
            product.setValue(owner + suffix);
            product.setName(owner + suffix);
            product.setM_Product_Category_ID(ownedCategoryId > 0 ? ownedCategoryId : template.getM_Product_Category_ID());
            product.setC_TaxCategory_ID(template.getC_TaxCategory_ID());
            product.setC_UOM_ID(template.getC_UOM_ID());
            product.setM_AttributeSet_ID(0);
            // Save may bootstrap costs; all hooks receive this named transaction.
            product.saveEx();
            productId = product.get_ID();
            assertTrue(productId > 0);
            assertNotEquals(TEMPLATE_PRODUCT_ID, productId, "Fixture must own a new product");
            assertOwned(product);
            assertEquals(MAcctSchema.COSTINGLEVEL_Client, product.getCostingLevel(schema));
            assertEquals(0, count("M_Transaction"), "Fixture starts without inventory history");
            for (MCost cost : new Query(ctx, MCost.Table_Name, "M_Product_ID=?", name)
                    .setParameters(productId).list(MCost.class)) {
                assertOwned(cost);
            }
        }

        Object[] dimensionParameters() {
            return new Object[] {CLIENT_ID, productId, SCHEMA_ID,
                    schema.getM_CostType_ID(), material.get_ID()};
        }

        Query costQuery() {
            return new Query(ctx, MCost.Table_Name, DIMENSION, name)
                    .setParameters(dimensionParameters()); // include inactive rows in uniqueness checks
        }

        MCost bootstrap() {
            return MCost.getOrCreate(product, 0, schema, 0, 0,
                    schema.getM_CostType_ID(), material.get_ID());
        }

        void assertDimension(MCost cost) {
            assertNotNull(cost, "Bootstrap returned no dimension");
            assertEquals(CLIENT_ID, cost.getAD_Client_ID());
            assertEquals(productId, cost.getM_Product_ID());
            assertEquals(SCHEMA_ID, cost.getC_AcctSchema_ID());
            assertEquals(schema.getM_CostType_ID(), cost.getM_CostType_ID());
            assertEquals(material.get_ID(), cost.getM_CostElement_ID());
            assertEquals(0, cost.getAD_Org_ID());
            assertEquals(0, cost.getM_Warehouse_ID());
            assertEquals(0, cost.getM_AttributeSetInstance_ID());
            assertOwned(cost);
            assertTrue(cost.isActive());
        }

        MInOutLine movement(boolean sales, String qty, Timestamp day) {
            return movement(sales, qty, "40", day);
        }

        MInOutLine draftMovement(boolean sales, String qty, String price, Timestamp day) {
            MOrderLine orderLine = order(sales, qty, price, day);
            MDocType type = documentType(sales ? MDocType.DOCBASETYPE_MaterialDelivery
                    : MDocType.DOCBASETYPE_MaterialReceipt, sales);
            MInOut document = new MInOut(ctx, 0, name);
            document.setAD_Org_ID(ORG_ID);
            document.setDocumentNo(owner.substring(0, 20) + "-M" + ++documentSequence);
            document.setDescription(owner);
            document.setIsSOTrx(sales);
            document.setC_DocType_ID(type.get_ID());
            document.setMovementType(sales ? "C-" : "V+");
            document.setMovementDate(day);
            document.setDateAcct(day);
            document.setM_Warehouse_ID(WAREHOUSE_ID);
            document.setC_BPartner_ID(partnerId);
            document.setC_BPartner_Location_ID(partnerLocation.get_ID());
            document.setC_Order_ID(orderLine.getC_Order_ID());
            document.saveEx();
            MInOutLine line = new MInOutLine(document);
            line.setC_OrderLine_ID(orderLine.get_ID());
            line.setM_Product_ID(productId);
            line.setC_UOM_ID(product.getC_UOM_ID());
            line.setM_Locator_ID(LOCATOR_ID);
            line.setMovementQty(new BigDecimal(qty));
            line.setDescription(owner);
            line.saveEx();
            assertOwned(document);
            assertOwned(line);
            return line;
        }

        MInOutLine movement(boolean sales, String qty, String price, Timestamp day) {
            MInOutLine line = draftMovement(sales, qty, price, day);
            MInOut document = line.getParent();
            MDocType type = new MDocType(ctx, document.getC_DocType_ID(), name);
            assertTrue(document.processIt(DocAction.ACTION_Complete), document.getProcessMsg());
            document.saveEx();
            MInOut saved = new MInOut(ctx, document.get_ID(), name);
            assertOwned(saved);
            assertEquals(DocAction.STATUS_Completed, saved.getDocStatus(), saved.getProcessMsg());
            assertTrue(saved.isProcessed());
            assertEquals(sales, saved.isSOTrx());
            assertEquals(day, saved.getDateAcct());
            assertEquals(day, saved.getMovementDate());
            assertEquals(type.get_ID(), saved.getC_DocType_ID());
            assertEquals(ORG_ID, saved.getAD_Org_ID());
            return line;
        }

        MOrderLine order(boolean sales, String qty, String price, Timestamp day) {
            MDocType type = documentType(sales ? MDocType.DOCBASETYPE_SalesOrder
                    : MDocType.DOCBASETYPE_PurchaseOrder, sales);
            MPriceList priceList = sales ? new Query(ctx, MPriceList.Table_Name,
                    "AD_Client_ID=? AND IsSOPriceList='Y' AND C_Currency_ID=?", name)
                    .setParameters(CLIENT_ID, CommonGWData.USD_CURRENCY_ID)
                    .setOnlyActiveRecords(true).setOrderBy("M_PriceList_ID").first() : purchasePriceList;
            assertNotNull(priceList, "Missing price list for owned order");
            MPriceListVersion version = new Query(ctx, MPriceListVersion.Table_Name,
                    "M_PriceList_ID=? AND ValidFrom<=?", name)
                    .setParameters(priceList.get_ID(), day).setOnlyActiveRecords(true)
                    .setOrderBy("ValidFrom DESC, M_PriceList_Version_ID DESC").first();
            assertNotNull(version, "Missing price list version for fixture date");
            MProductPrice productPrice = new Query(ctx, MProductPrice.Table_Name,
                    "M_PriceList_Version_ID=? AND M_Product_ID=?", name)
                    .setParameters(version.get_ID(), productId).firstOnly();
            if (productPrice == null)
                productPrice = new MProductPrice(ctx, version.get_ID(), productId, name);
            productPrice.setPrices(new BigDecimal(price), new BigDecimal(price), Env.ZERO);
            productPrice.saveEx();
            MOrder document = new MOrder(ctx, 0, name);
            document.setAD_Org_ID(ORG_ID);
            document.setIsSOTrx(sales);
            document.setC_DocTypeTarget_ID(type.get_ID());
            document.setDocumentNo(owner.substring(0, 20) + "-O" + ++documentSequence);
            document.setDescription(owner);
            document.setBPartner(partner);
            document.setC_BPartner_Location_ID(partnerLocation.get_ID());
            document.setM_PriceList_ID(priceList.get_ID());
            document.setM_Warehouse_ID(WAREHOUSE_ID);
            document.setDateOrdered(day);
            document.setDateAcct(day);
            document.setDatePromised(day);
            document.setDeliveryRule(MOrder.DELIVERYRULE_Force);
            document.saveEx();
            MOrderLine line = new MOrderLine(document);
            line.setProduct(product);
            line.setQty(new BigDecimal(qty));
            line.setPrice(new BigDecimal(price));
            line.setDescription(owner);
            line.saveEx();
            document.setDocAction(DocAction.ACTION_Complete);
            assertTrue(document.processIt(DocAction.ACTION_Complete), document.getProcessMsg());
            document.saveEx();
            assertEquals(DocAction.STATUS_Completed, document.getDocStatus());
            assertOwned(document);
            assertOwned(line);
            return line;
        }

        MInvoiceLine invoice(MInOutLine receipt, Timestamp day) {
            return invoice(receipt, "10", "40", day);
        }

        MInvoiceLine invoice(MInOutLine receipt, String qty, String price, Timestamp day) {
            return invoice(receipt,qty,price,day,false);
        }
        MInvoiceLine invoice(MInOutLine receipt, String qty, String price, Timestamp day, boolean fromReceipt) {
            MDocType type = documentType(MDocType.DOCBASETYPE_APInvoice, false);
            MInvoice document = new MInvoice(ctx, 0, name);
            document.setAD_Org_ID(ORG_ID);
            document.setDocumentNo(owner.substring(0, 20) + "-I" + ++documentSequence);
            document.setDescription(owner);
            document.setIsSOTrx(false);
            document.setC_DocTypeTarget_ID(type.get_ID());
            document.setC_BPartner_ID(partnerId);
            document.setC_BPartner_Location_ID(partnerLocation.get_ID());
            assertNotNull(purchasePriceList, "Check document preconditions before invoicing");
            document.setM_PriceList_ID(purchasePriceList.get_ID());
            document.setDateInvoiced(day);
            document.setDateAcct(day);
            document.saveEx();
            MInvoiceLine line = new MInvoiceLine(document);
            line.setM_Product_ID(productId);
            line.setM_InOutLine_ID(receipt.get_ID());
            line.setM_AttributeSetInstance_ID(new MInOutLine(ctx, receipt.get_ID(), name).getM_AttributeSetInstance_ID());
            if(fromReceipt) line.setShipLine(new MInOutLine(ctx,receipt.get_ID(),name));
            line.setQty(new BigDecimal(qty));
            line.setPrice(new BigDecimal(price));
            line.setDescription(owner);
            line.saveEx();
            assertOwned(document);
            assertOwned(line);
            assertTrue(document.processIt(DocAction.ACTION_Complete), document.getProcessMsg());
            document.saveEx();
            MInvoice saved = new MInvoice(ctx, document.get_ID(), name);
            assertOwned(saved);
            assertEquals(DocAction.STATUS_Completed, saved.getDocStatus(), saved.getProcessMsg());
            assertTrue(saved.isProcessed());
            assertFalse(saved.isSOTrx());
            assertEquals(type.get_ID(), saved.getC_DocType_ID());
            assertEquals(day, saved.getDateAcct());
            assertEquals(day, saved.getDateInvoiced());
            MInvoiceLine savedLine = new MInvoiceLine(ctx, line.get_ID(), name);
            assertOwned(savedLine);
            assertEquals(productId, savedLine.getM_Product_ID());
            assertEquals(receipt.get_ID(), savedLine.getM_InOutLine_ID());
            assertDecimal(qty, savedLine.getQtyInvoiced(), "AP invoice quantity");
            assertDecimal(price, savedLine.getPriceActual(), "AP invoice unit price");
            return savedLine;
        }

        List<List<String>> snapshotUnchecked(String table) {
            try { return snapshot(table, name); }
            catch (SQLException e) { throw new IllegalStateException(e); }
        }

        void postInvoiceAndMatch(MInvoiceLine invoice, MInOutLine receipt) {
            post(MInvoice.Table_ID, invoice.getC_Invoice_ID());
            MMatchInv match = new Query(ctx, MMatchInv.Table_Name,
                    "C_InvoiceLine_ID=? AND M_InOutLine_ID=?", name)
                    .setParameters(invoice.get_ID(), receipt.get_ID()).firstOnly();
            assertNotNull(match);
            post(MMatchInv.Table_ID, match.get_ID());
        }

        MLandedCostAllocation pendingLandedCost(MInOutLine receipt, String amount, Timestamp day) {
            return pendingLandedCost(receipt, amount, day, false);
        }

        MLandedCostAllocation pendingLandedCost(MInOutLine receipt, String amount, Timestamp day, boolean credit) {
            MDocType type = documentType(credit ? MDocType.DOCBASETYPE_APCreditMemo : MDocType.DOCBASETYPE_APInvoice, false);
            MInvoice document = new MInvoice(ctx, 0, name);
            document.setAD_Org_ID(ORG_ID);
            document.setDocumentNo(owner.substring(0, 20) + "-L" + ++documentSequence);
            document.setDescription(owner);
            document.setIsSOTrx(false);
            document.setC_DocTypeTarget_ID(type.get_ID());
            document.setC_BPartner_ID(partnerId);
            document.setC_BPartner_Location_ID(partnerLocation.get_ID());
            document.setM_PriceList_ID(purchasePriceList.get_ID());
            document.setDateInvoiced(day);
            document.setDateAcct(day);
            document.saveEx();
            MCharge charge = new Query(ctx, MCharge.Table_Name,
                    "AD_Client_ID=? AND C_TaxCategory_ID=?", name)
                    .setParameters(CLIENT_ID, product.getC_TaxCategory_ID())
                    .setOnlyActiveRecords(true).setOrderBy("C_Charge_ID").first();
            assertNotNull(charge, "Fixture requires a seed charge with the product tax category");
            MInvoiceLine line = new MInvoiceLine(document);
            line.setC_Charge_ID(charge.get_ID());
            line.setQty(Env.ONE);
            line.setPrice(new BigDecimal(amount));
            line.setDescription(owner);
            line.saveEx();
            assertTrue(document.processIt(DocAction.ACTION_Complete), document.getProcessMsg());
            document.saveEx();
            // Simulate an already imported allocation awaiting costing, without
            // invoking the independently selected historical receipt pipeline.
            MLandedCostAllocation allocation = new MLandedCostAllocation(line, material.get_ID());
            allocation.setM_Product_ID(productId);
            allocation.setM_InOutLine_ID(receipt.get_ID());
            allocation.setM_AttributeSetInstance_ID(receipt.getM_AttributeSetInstance_ID());
            allocation.setQty(receipt.getMovementQty());
            allocation.setBase(receipt.getMovementQty());
            allocation.setAmt(new BigDecimal(amount));
            allocation.saveEx();
            return allocation;
        }

        MInvoice realLandedCostInvoice(MInOutLine receipt, String amount, Timestamp day) {
            MDocType type = documentType(MDocType.DOCBASETYPE_APInvoice, false);
            MInvoice document = new MInvoice(ctx, 0, name);
            document.setAD_Org_ID(ORG_ID);
            document.setDocumentNo(owner.substring(0, 20) + "-L" + ++documentSequence);
            document.setDescription(owner);
            document.setIsSOTrx(false);
            document.setC_DocTypeTarget_ID(type.get_ID());
            document.setC_BPartner_ID(partnerId);
            document.setC_BPartner_Location_ID(partnerLocation.get_ID());
            document.setM_PriceList_ID(purchasePriceList.get_ID());
            document.setDateInvoiced(day);
            document.setDateAcct(day);
            document.saveEx();
            MCharge charge = new Query(ctx, MCharge.Table_Name,
                    "AD_Client_ID=? AND C_TaxCategory_ID=?", name)
                    .setParameters(CLIENT_ID, product.getC_TaxCategory_ID())
                    .setOnlyActiveRecords(true).setOrderBy("C_Charge_ID").first();
            assertNotNull(charge, "Fixture requires a seed charge with the product tax category");
            MInvoiceLine line = new MInvoiceLine(document);
            line.setC_Charge_ID(charge.get_ID());
            line.setQty(Env.ONE);
            line.setPrice(new BigDecimal(amount));
            line.setDescription(owner);
            line.saveEx();
            MLandedCost landed = new MLandedCost(ctx,0,name);
            landed.setC_InvoiceLine_ID(line.get_ID());landed.setM_InOutLine_ID(receipt.get_ID());
            landed.setM_CostElement_ID(material.get_ID());landed.saveEx();
            assertTrue(document.processIt(DocAction.ACTION_Complete), document.getProcessMsg());
            document.saveEx();
            return document;
        }

        void assertCostState(String quantity, String value, String unitPrice) {
            MCost cost = costQuery().firstOnly();
            assertNotNull(cost, "Missing owned cost dimension");
            assertDecimal(quantity, cost.getCurrentQty(), "Current stock quantity");
            assertDecimal(quantity, cost.getCumulatedQty(), "Accumulated stock quantity");
            assertDecimal(value, cost.getCumulatedAmt(), "Inventory value");
            assertDecimal(unitPrice, cost.getCurrentCostPrice(), "Average unit cost");
            MCostDetail last = new Query(ctx, MCostDetail.Table_Name, COST_KEY, name)
                    .setParameters(dimensionParameters()).setOrderBy("SeqNo DESC, M_CostDetail_ID DESC").first();
            assertNotNull(last, "Missing cost detail for the owned movement");
            AverageInvoiceCostingMethod method = new AverageInvoiceCostingMethod();
            assertDecimal(quantity, method.getNewAccumulatedQuantity(last), "Detail accumulated quantity");
            assertDecimal(value, method.getNewAccumulatedAmount(last), "Detail inventory value");
        }

        MDocType documentType(String baseType, boolean sales) {
            // Resolve by semantics, never assume seed numeric IDs imply direction.
            MDocType type = new Query(ctx, MDocType.Table_Name,
                    "AD_Client_ID=? AND AD_Org_ID IN (0,?) AND DocBaseType=? AND IsSOTrx=?"
                    + " AND IsCreateCounter='N' AND IsOverwriteSeqOnComplete='N' AND IsOverwriteDateOnComplete='N'"
                    + " AND (DocBaseType<>'SOO' OR DocSubTypeSO='SO')"
                    + " AND (DocBaseType<>'POO' OR DocSubTypeSO IS NULL)", name)
                    .setParameters(CLIENT_ID, ORG_ID, baseType, sales)
                    .setOnlyActiveRecords(true).setOrderBy("IsDefault DESC, C_DocType_ID").first();
            assertNotNull(type, "Missing suitable document type for " + baseType);
            return type;
        }

        int count(String table) {
            return new Query(ctx, table, "M_Product_ID=?", name).setParameters(productId).count();
        }

        List<List<String>> snapshot(String table, String trxName) throws SQLException {
            List<List<String>> rows = new ArrayList<>();
            try (PreparedStatement statement = Trx.get(trxName, false).getConnection()
                    .prepareStatement("SELECT * FROM " + table + " WHERE AD_Client_ID=?")) {
                statement.setInt(1, CLIENT_ID);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        List<String> row = new ArrayList<>();
                        for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
                            row.add(result.getString(i));
                        }
                        rows.add(row);
                    }
                }
            }
            // Stable ordering without assuming a surrogate key (M_Cost is composite).
            rows.sort((left, right) -> {
                for (int i = 0; i < left.size(); i++) {
                    int compared = java.util.Comparator.nullsFirst(String::compareTo)
                            .compare(left.get(i), right.get(i));
                    if (compared != 0) return compared;
                }
                return 0;
            });
            return rows;
        }

        static void rollbackAndClose(Trx trx) {
            Throwable failure = null;
            try {
                assertTrue(trx.rollback(true), "Rollback failed: " + trx.getTrxName());
            } catch (SQLException | RuntimeException | Error e) {
                failure = e;
            }
            // Never allow Trx.close() to commit after an unexpected unchecked rollback failure.
            if (trx.isActive()) {
                try {
                    Connection connection = trx.getConnection(false);
                    if (connection != null) connection.close(); // discard; never commit
                } catch (SQLException | RuntimeException | Error e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
                throw new AssertionError("Rollback left transaction active; refused Trx.close(): "
                        + trx.getTrxName(), failure);
            }
            // rollback(true) deactivates Trx on success and on SQLException.
            try {
                assertTrue(trx.close(), "Transaction close failed: " + trx.getTrxName());
            } catch (RuntimeException | Error e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
            if (failure != null) throw new AssertionError("Explicit rollback/close failure", failure);
        }

        @Override
        public void close() {
            try {
                verifyRestoration();
            } finally {
                restoreContext();
            }
        }

        private void restoreContext() {
            originalPeriodStatus.forEach((control, status) -> control.setPeriodStatus(status));
            Env.getCtx().clear();
            Env.getCtx().putAll(originalContext);
        }

        private void verifyRestoration() {
            rollbackAndClose(transaction);
            // A fresh transaction verifies persisted state, including existing shared rows.
            String checkName = Trx.createTrxName("4411-rollback-check");
            Trx check = Trx.get(checkName, true);
            Throwable failure = null;
            try {
                DatabaseIdentity.configured().verify(check.getConnection());
                for (Map.Entry<String, List<List<String>>> entry : sharedBefore.entrySet()) {
                    assertEquals(entry.getValue(), snapshot(entry.getKey(), checkName),
                            entry.getKey() + " existing shared rows changed after rollback");
                }
                assertEquals(0, new Query(ctx, MProduct.Table_Name, "Value=?", checkName)
                        .setParameters(owner).count(), "Owned product survived rollback");
                assertEquals(0, new Query(ctx, MBPartner.Table_Name, "Value=?", checkName)
                        .setParameters(owner).count(), "Owned partner survived rollback");
                if (ownsPartner) {
                    for (String table : new String[] {"C_BPartner", "C_BPartner_Location",
                            "C_BP_Customer_Acct", "C_BP_Vendor_Acct", "C_BP_Employee_Acct"}) {
                        assertEquals(0, new Query(ctx, table, "C_BPartner_ID=?", checkName)
                                .setParameters(partnerId).count(), table + " survived rollback");
                    }
                }
                assertEquals(0, new Query(ctx, MLocation.Table_Name, "Address1=?", checkName)
                        .setParameters(owner).count(), "Tagged address survived rollback");
                assertEquals(0, new Query(ctx, MBPartnerLocation.Table_Name, "Name=?", checkName)
                        .setParameters(owner).count(), "Tagged partner location survived rollback");
                if (locationId > 0) {
                    assertEquals(0, new Query(ctx, MLocation.Table_Name, "C_Location_ID=?", checkName)
                            .setParameters(locationId).count(), "Owned address survived rollback");
                }
                if (productId > 0) {
                    for (String table : new String[] {"M_Product", "M_Product_Acct",
                            "M_Product_Costing", "M_ProductPrice", "M_Cost", "M_CostDetail", "M_Storage",
                            "M_Transaction", "M_InOutLine", "M_MovementLine", "C_LandedCostAllocation", "C_InvoiceLine", "C_OrderLine", "M_MatchInv", "M_MatchPO"}) {
                        assertEquals(0, new Query(ctx, table, "M_Product_ID=?", checkName)
                                .setParameters(productId).count(), table + " survived rollback");
                    }
                }
                for (String table : new String[] {"M_InOut", "M_Movement", "C_Invoice", "C_Order"}) {
                    assertEquals(0, new Query(ctx, table, "Description=?", checkName)
                            .setParameters(owner).count(), table + " header survived rollback");
                }
            } catch (SQLException | RuntimeException | Error e) {
                failure = e;
                throw new AssertionError("Rollback restoration verification failed", e);
            } finally {
                try { rollbackAndClose(check); }
                catch (RuntimeException | Error cleanup) {
                    if (failure != null) failure.addSuppressed(cleanup);
                    else throw cleanup;
                }
            }
        }
    }
}
