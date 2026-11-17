/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution                       *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 * For the text or an alternative of this public license, you may reach us    *
 * Copyright (C) 2003-2010 e-Evolution,SC. All Rights Reserved.               *
 * Contributor(s): victor.perez@e-evolution.com http://www.e-evolution.com    *
 *****************************************************************************/

package org.eevolution.manufacturing.process;

import java.sql.SQLException;
import java.sql.Connection;
import java.sql.Savepoint;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.adempiere.core.domains.models.I_M_Cost;
import org.adempiere.engine.CostEngineFactory;
import org.adempiere.engine.CostEngine;
import org.compiere.model.MAcctSchema;
import org.compiere.model.MDocType;
import org.compiere.model.MPeriod;
import org.compiere.model.MPeriodControl;
import org.compiere.model.Query;
import org.adempiere.engine.IDocumentLine;
import org.compiere.model.MCostDetail;
import org.compiere.model.MCostElement;
import org.compiere.model.MCostType;
import org.compiere.model.MInOutLine;
import org.compiere.model.MLandedCostAllocation;
import org.compiere.model.MMatchInv;
import org.compiere.model.MMatchPO;
import org.compiere.model.MTransaction;
import org.compiere.util.DB;
import org.compiere.util.KeyNamePair;
import org.compiere.util.Trx;
import org.compiere.util.TimeUtil;
import org.eevolution.manufacturing.model.MPPCostCollector;
import org.eevolution.manufacturing.services.StandardCostCollector;

/**
 * Regenerate Cost Detail The Generate Cost Transaction process allows the
 * detailed cost calculation and cost generation beginning from a date. If you
 * have not yet set COGs Adjustment, you should execute this process before a
 * period's end in order to fix the cost layers.
 *
 * @author victor.perez@e-evolution.com, www.e-evolution.com
 * @author Yamel Senih, ysenih@erpcya.com, ERPCyA http://www.erpcya.com
 *			<li> FR [ 405 ] Wrong Syntax of Delete query in GenerateCostDetail.java
 *			@see https://github.com/adempiere/adempiere/issues/405
 */
public class GenerateCostDetail extends GenerateCostDetailAbstract {
    /**
     * Variables *
     */
    private ArrayList<Object> deleteParameters;
    private ArrayList<Object> resetCostParameters;
    private List<MAcctSchema> acctSchemas = new ArrayList<MAcctSchema>();
    private List<MCostType> costTypes = new ArrayList<MCostType>();
    private List<MCostElement> costElements = new ArrayList<MCostElement>();
    private StringBuffer deleteCostDetailWhereClause;
    private StringBuffer resetCostWhereClause;
    //private List<MTransaction> deferredTransactions = new ArrayList<MTransaction>();
    //private List<I_M_Product> deferredProducts = new ArrayList<I_M_Product>();

    /**
     * Prepare - e.g., get Parameters.
     */
    protected void prepare() {
        super.prepare();


    } // prepare

    /**
     * execute the Generate Cost Detail
     */
    protected String doIt() throws Exception {
        generateCostDetail();
        return "@Ok@";

    }

    /**
     * Delete Cost Detail
     *
     * @throws SQLException
     */
    private void deleteCostDetail(String trxName) throws SQLException {
        StringBuffer sqlDelete;
        //	BR [ 405 ]
        sqlDelete = new StringBuffer("DELETE FROM M_CostDetail WHERE ");
        sqlDelete.append(deleteCostDetailWhereClause);
        DB.executeUpdateEx(sqlDelete.toString(), deleteParameters.toArray(), trxName);
    }

    /**
     * Reset Cost Dimension
     * @param costingMethod
     * @param trxName
     * @throws SQLException
     */
    private void resetCostDimension(String costingMethod, String trxName) throws SQLException {
        StringBuffer sqlReset;
        sqlReset = new StringBuffer("UPDATE M_Cost SET ");

        // Delete M_Cost not for others than average
        if (MCostType.COSTINGMETHOD_AverageInvoice.equals(costingMethod)) {
            sqlReset.append(I_M_Cost.COLUMNNAME_CurrentCostPrice).append("=0.0,");
            sqlReset.append(I_M_Cost.COLUMNNAME_CurrentCostPriceLL).append("= 0.0,");
        }

        sqlReset.append(I_M_Cost.COLUMNNAME_CurrentQty).append("= 0.0,");
        sqlReset.append(I_M_Cost.COLUMNNAME_CumulatedAmt).append("= 0.0,");
        sqlReset.append(I_M_Cost.COLUMNNAME_CumulatedAmtLL).append("= 0.0,");
        sqlReset.append(I_M_Cost.COLUMNNAME_CumulatedQty).append("= 0.0 ");
        sqlReset.append(" WHERE ").append(resetCostWhereClause);
        DB.executeUpdateEx(sqlReset.toString(),
                resetCostParameters.toArray(), trxName);
    }


    /**
     * Setup the collections
     *
     * @throws SQLException
     */
    private void setup() {
        acctSchemas.clear();
        costTypes.clear();
        costElements.clear();

        if (getAcctSchemaId() > 0)
            acctSchemas.add(MAcctSchema.get(getCtx(), getAcctSchemaId() , get_TrxName()));
        else
            acctSchemas = Arrays.asList(MAcctSchema
                    .getClientAcctSchema(getCtx(), getAD_Client_ID(),
                            get_TrxName()));

        if (getCostTypeId() > 0)
            costTypes.add(new MCostType(getCtx(), getCostTypeId(),
                    get_TrxName()));
        else
            costTypes = MCostType.get(getCtx(), get_TrxName());

        if (getCostElementId() > 0)
            costElements.add(MCostElement.get(getCtx(), getCostElementId()));
        else
            costElements = MCostElement.getCostElement(getCtx(), get_TrxName());
    }

    /**
     * Apply Criteria for where clause
     *
     * @param accountSchemaId
     * @param costTypeId
     * @param costElementId
     * @param productId
     * @param dateAccount
     */
    private void applyCriteria(int accountSchemaId, int costTypeId,
                               int costElementId, int productId, Timestamp dateAccount, Timestamp dateAccountTo) {
        deleteParameters = new ArrayList<Object>();
        resetCostParameters = new ArrayList<Object>();
        deleteCostDetailWhereClause = new StringBuffer("AD_Client_ID=?");
        resetCostWhereClause = new StringBuffer("AD_Client_ID=?");
        deleteParameters.add(getAD_Client_ID());
        resetCostParameters.add(getAD_Client_ID());

        if (accountSchemaId > 0) {
            deleteCostDetailWhereClause.append(" AND ").append(MCostDetail.COLUMNNAME_C_AcctSchema_ID).append("=? ");
            deleteParameters.add(accountSchemaId);
            resetCostWhereClause.append(" AND ").append(MCostDetail.COLUMNNAME_C_AcctSchema_ID).append("=? ");
            resetCostParameters.add(accountSchemaId);
        }
        if (costTypeId > 0) {
            deleteCostDetailWhereClause.append(" AND ").append(MCostDetail.COLUMNNAME_M_CostType_ID).append("=? ");
            deleteParameters.add(costTypeId);
            resetCostWhereClause.append(" AND ").append(MCostDetail.COLUMNNAME_M_CostType_ID).append("=? ");
            resetCostParameters.add(costTypeId);//SHW
        }
        if (costElementId > 0) {
            deleteCostDetailWhereClause.append(" AND ").append(MCostDetail.COLUMNNAME_M_CostElement_ID).append("=? ");
            deleteParameters.add(costElementId);
            resetCostWhereClause.append(" AND ").append(MCostDetail.COLUMNNAME_M_CostElement_ID).append("=? ");
            resetCostParameters.add(costElementId);//SHW
        }
        if (productId > 0) {
            deleteCostDetailWhereClause.append(" AND ").append(MCostDetail.COLUMNNAME_M_Product_ID).append("=? ");
            deleteParameters.add(productId);
            resetCostWhereClause.append(" AND ").append(MCostDetail.COLUMNNAME_M_Product_ID).append("=? ");
            resetCostParameters.add(productId);
        }
        if (dateAccount != null) {
            deleteCostDetailWhereClause.append(" AND ").append("TRUNC(" + MCostDetail.COLUMNNAME_DateAcct + ")").append(">=? ");
            deleteParameters.add(dateAccount);
        }
        if (dateAccountTo != null) {
            deleteCostDetailWhereClause.append(" AND ").append("TRUNC(" + MCostDetail.COLUMNNAME_DateAcct + ")").append("<=? ");
            deleteParameters.add(dateAccountTo);
        }
        //avoid rest cost dimension if not exist transaction conserve of last cost calculated
        resetCostWhereClause.append(" AND EXISTS ( SELECT 1 FROM RV_Transaction WHERE M_Product_ID=? AND TRUNC(DateAcct)>=? AND TRUNC(DateAcct)<=?)");
        resetCostParameters.add(productId);
        resetCostParameters.add(dateAccount);
        resetCostParameters.add(dateAccountTo);
        return;
    }

    /** Rebuild in the caller's transaction; never commit a partial product batch. */
    public void generateCostDetail() {
        if (getDateAcct() == null || getDateAcctTo() == null
                || getDateAcctTo().before(getDateAcct()))
            throw new IllegalArgumentException("An ordered, bounded accounting date range is required");
        if (get_TrxName() == null || Trx.get(get_TrxName(), false) == null)
            throw new IllegalStateException("Cost regeneration requires a caller-owned transaction");
        setDateAcct(TimeUtil.getDay(getDateAcct()));
        setDateAcctTo(TimeUtil.getDay(getDateAcctTo()));
        setup();
        Connection connection = Trx.get(get_TrxName(), false).getConnection();
        Savepoint savepoint = null;
        try {
            savepoint = connection.setSavepoint();
            KeyNamePair[] transactions = getTransactionIdsByDateAcct();
            // Validate the entire selection before deleting/resetting the first product.
            List<MLandedCostAllocation> lateCosts = getIndependentLandedCosts();
            validateRegeneration(transactions, lateCosts);
            lockLateCostDimensions(lateCosts, connection);
            java.util.Set<Integer> resetProducts = new java.util.HashSet<Integer>();
            for (KeyNamePair keyNamePair : transactions) {
                int productId = Integer.parseInt(keyNamePair.getName());
                if (resetProducts.add(productId)) {
                    for (MAcctSchema accountSchema : acctSchemas)
                        for (MCostType costType : costTypes)
                            for (MCostElement costElement : costElements) {
                                applyCriteria(accountSchema.get_ID(), costType.get_ID(), costElement.get_ID(),
                                        productId, getDateAcct(), getDateAcctTo());
                                deleteCostDetail(get_TrxName());
                                resetCostDimension(costType.getCostingMethod(), get_TrxName());
                                generateCostCollectorNotTransaction(accountSchema, costType, productId, get_TrxName());
                            }
                }
                MTransaction transaction = new MTransaction(getCtx(), keyNamePair.getKey(), get_TrxName());
                for (MAcctSchema accountSchema : acctSchemas)
                    for (MCostType costType : costTypes)
                        for (MCostElement costElement : costElements)
                            generateCostDetail(accountSchema, costType, costElement, transaction);
                for (MAcctSchema accountSchema : acctSchemas) {
                    int accountingType = MCostType.get(accountSchema, transaction.getM_Product_ID(),
                            transaction.getAD_Org_ID()).get_ID();
                    if (costTypes.stream().anyMatch(type -> type.get_ID() == accountingType))
                        CostEngine.validateSalesCost(accountSchema, transaction, transaction.getDocumentLine());
                }
            }
            for (MLandedCostAllocation allocation : lateCosts)
                generateIndependentLandedCost(allocation);
            connection.releaseSavepoint(savepoint);
        } catch (Exception failure) {
            if (savepoint != null) {
                try { connection.rollback(savepoint); }
                catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            }
            throw new IllegalStateException("Cost regeneration failed; caller transaction must roll back", failure);
        }
    }

    private boolean isInDateRange(Timestamp date) {
        Timestamp day = TimeUtil.getDay(date);
        return !day.before(getDateAcct()) && !day.after(getDateAcctTo());
    }

    private void requireOpenPeriod(Timestamp date, String docBaseType, int orgId) {
        if (!MPeriod.isOpen(getCtx(), date, docBaseType, orgId, get_TrxName()))
            throw new IllegalStateException("Cost regeneration cannot change a closed period: " + date);
    }

    private void validateTransaction(MTransaction transaction) {
        if (transaction.getAD_Client_ID() != getAD_Client_ID())
            throw new IllegalStateException("Transaction belongs to another client");
        IDocumentLine line = transaction.getDocumentLine();
        requireOpenPeriod(line.getDateAcct(), MDocType.get(getCtx(), line.getC_DocType_ID()).getDocBaseType(),
                transaction.getAD_Org_ID());
        if (transaction.getM_InOutLine_ID() > 0) {
            MInOutLine receipt = (MInOutLine) transaction.getM_InOutLine();
            // clearAccounting resets all related matches, including dates outside
            // the selected receipt range. Reject a range that cannot rebuild them.
            for (MMatchPO match : MMatchPO.getInOutLine(receipt)) {
                if (!isInDateRange(match.getDateAcct()))
                    throw new IllegalStateException("Regeneration range excludes a related purchase match");
                requireOpenPeriod(match.getDateAcct(), MPeriodControl.DOCBASETYPE_MatchPO, match.getAD_Org_ID());
            }
            for (MMatchInv match : MMatchInv.getInOutLine(receipt)) {
                if (!isInDateRange(match.getDateAcct()))
                    throw new IllegalStateException("Regeneration range excludes a related invoice match");
                requireOpenPeriod(match.getDateAcct(), MPeriodControl.DOCBASETYPE_MatchInvoice, match.getAD_Org_ID());
            }
            for (MCostElement element : costElements)
                for (MLandedCostAllocation allocation : MLandedCostAllocation.getOfInOutline(receipt, element.get_ID())) {
                    if (!isInDateRange(allocation.getDateAcct()))
                        throw new IllegalStateException("Regeneration range excludes a landed cost allocation");
                    requireOpenPeriod(allocation.getDateAcct(), MDocType.get(getCtx(), allocation.getC_DocType_ID())
                            .getDocBaseType(), allocation.getAD_Org_ID());
                }
        }
    }

    private void validateRegeneration(KeyNamePair[] transactions, List<MLandedCostAllocation> lateCosts) {
        java.util.Set<Integer> products = new java.util.HashSet<Integer>();
        for (MAcctSchema schema : acctSchemas)
            if (schema.getAD_Client_ID() != getAD_Client_ID())
                throw new IllegalStateException("Accounting schema belongs to another client");
        for (KeyNamePair selected : transactions) {
            MTransaction transaction = new MTransaction(getCtx(), selected.getKey(), get_TrxName());
            validateTransaction(transaction);
            products.add(transaction.getM_Product_ID());
        }
        for (MLandedCostAllocation allocation : lateCosts) {
            validateIndependentLandedCost(allocation);
            products.add(allocation.getM_Product_ID());
        }
        for (int productId : products) {
            if (DB.getSQLValueEx(get_TrxName(), "SELECT COUNT(*) FROM RV_Transaction"
                    + " WHERE AD_Client_ID=? AND M_Product_ID=? AND TRUNC(DateAcct)>?",
                    getAD_Client_ID(), productId, getDateAcctTo()) > 0)
                throw new IllegalStateException("Regeneration must include the complete costing tail");
            // Non-transaction manufacturing collectors need a separate replay contract.
            if (!StandardCostCollector.getCostCollectorNotTransaction(getCtx(), productId,
                    getDateAcct(), TimeUtil.addDays(getDateAcctTo(), 1), get_TrxName()).isEmpty())
                throw new IllegalStateException("Non-transaction cost collectors are not supported by safe regeneration");
            for (MAcctSchema schema : acctSchemas)
                for (MCostType type : costTypes)
                    for (MCostElement element : costElements) {
                        List<MCostDetail> details = new Query(getCtx(), MCostDetail.Table_Name,
                                "AD_Client_ID=? AND M_Product_ID=? AND C_AcctSchema_ID=? AND M_CostType_ID=?"
                                + " AND M_CostElement_ID=? AND TRUNC(DateAcct)>=?", get_TrxName())
                                .setParameters(getAD_Client_ID(), productId, schema.get_ID(), type.get_ID(),
                                        element.get_ID(), getDateAcct()).list();
                        for (MCostDetail detail : details) {
                            if (!isInDateRange(detail.getDateAcct()))
                                throw new IllegalStateException("Regeneration must include the complete costing tail");
                            if (detail.getM_Transaction_ID() <= 0)
                                throw new IllegalStateException("Cost detail without a physical transaction cannot be safely regenerated");
                            if (detail.getC_LandedCostAllocation_ID() > 0)
                                validateIndependentLandedCost(new MLandedCostAllocation(getCtx(),
                                        detail.getC_LandedCostAllocation_ID(), get_TrxName()));
                            else
                                validateTransaction(new MTransaction(getCtx(), detail.getM_Transaction_ID(), get_TrxName()));
                        }
                    }
        }
    }

    /** Select by the invoice date even when the receipt is before the rebuild range. */
    private List<MLandedCostAllocation> getIndependentLandedCosts() {
        if (costTypes.stream().noneMatch(type -> MCostType.COSTINGMETHOD_AverageInvoice.equals(type.getCostingMethod())))
            return java.util.Collections.emptyList();
        StringBuilder where = new StringBuilder("AD_Client_ID=? AND Amt<>0 AND EXISTS ("
                + "SELECT 1 FROM C_InvoiceLine il JOIN C_Invoice inv ON inv.C_Invoice_ID=il.C_Invoice_ID"
                + " JOIN M_InOutLine rl ON rl.M_InOutLine_ID=C_LandedCostAllocation.M_InOutLine_ID"
                + " JOIN M_InOut receipt ON receipt.M_InOut_ID=rl.M_InOut_ID"
                + " WHERE il.C_InvoiceLine_ID=C_LandedCostAllocation.C_InvoiceLine_ID"
                + " AND inv.DocStatus IN ('CO','CL') AND TRUNC(inv.DateAcct)>=? AND TRUNC(inv.DateAcct)<=?"
                + " AND TRUNC(receipt.DateAcct)<?)");
        List<Object> parameters = new ArrayList<Object>();
        parameters.add(getAD_Client_ID()); parameters.add(getDateAcct());
        parameters.add(getDateAcctTo()); parameters.add(getDateAcct());
        if (getProductId() > 0) {
            where.append(" AND M_Product_ID=?"); parameters.add(getProductId());
        }
        if (getProductCategoryId() > 0) {
            where.append(" AND EXISTS (SELECT 1 FROM M_Product p WHERE p.M_Product_ID=C_LandedCostAllocation.M_Product_ID"
                    + " AND p.M_Product_Category_ID=?)"); parameters.add(getProductCategoryId());
        }
        if (getCostElementId() > 0) {
            where.append(" AND M_CostElement_ID=?"); parameters.add(getCostElementId());
        }
        return new Query(getCtx(), MLandedCostAllocation.Table_Name, where.toString(), get_TrxName())
                .setParameters(parameters).setOnlyActiveRecords(true)
                .setOrderBy("(SELECT inv.DateAcct FROM C_InvoiceLine il JOIN C_Invoice inv"
                        + " ON inv.C_Invoice_ID=il.C_Invoice_ID WHERE il.C_InvoiceLine_ID=C_LandedCostAllocation.C_InvoiceLine_ID),"
                        + " C_LandedCostAllocation_ID").list();
    }

    private MTransaction validateIndependentLandedCost(MLandedCostAllocation allocation) {
        MInOutLine receipt = (MInOutLine) allocation.getM_InOutLine();
        if (allocation.getAD_Client_ID() != getAD_Client_ID() || receipt.getAD_Client_ID() != getAD_Client_ID()
                || allocation.getInvoice().getAD_Client_ID() != getAD_Client_ID()
                || receipt.getM_Product_ID() != allocation.getM_Product_ID()
                || receipt.getAD_Org_ID() != allocation.getAD_Org_ID())
            throw new IllegalStateException("Inconsistent landed cost source dimensions");
        if (!isInDateRange(allocation.getDateAcct()))
            throw new IllegalStateException("Landed cost invoice is outside the regeneration range");
        requireOpenPeriod(allocation.getDateAcct(), allocation.getInvoice().getC_DocType().getDocBaseType(),
                allocation.getAD_Org_ID());
        if (allocation.getQty().signum() <= 0 || allocation.getBase().signum() <= 0)
            throw new IllegalStateException("Landed cost requires a positive quantity and allocation basis");
        List<MTransaction> transactions = MTransaction.getByInOutLine(receipt);
        if (transactions.size() != 1 || !MTransaction.MOVEMENTTYPE_VendorReceipts.equals(transactions.get(0).getMovementType()))
            throw new IllegalStateException("Independent landed cost currently requires a single receipt transaction");
        return transactions.get(0);
    }

    private void lockLateCostDimensions(List<MLandedCostAllocation> allocations, Connection connection) throws SQLException {
        java.util.Set<Integer> products = new java.util.TreeSet<Integer>();
        for (MLandedCostAllocation allocation : allocations) products.add(allocation.getM_Product_ID());
        List<MAcctSchema> schemas = new ArrayList<MAcctSchema>(acctSchemas);
        schemas.sort(java.util.Comparator.comparingInt(MAcctSchema::get_ID));
        List<MCostType> types = new ArrayList<MCostType>(costTypes);
        types.sort(java.util.Comparator.comparingInt(MCostType::get_ID));
        List<MCostElement> elements = new ArrayList<MCostElement>(costElements);
        elements.sort(java.util.Comparator.comparingInt(MCostElement::get_ID));
        for (int product : products)
            for (MAcctSchema schema : schemas)
                for (MCostType type : types) {
                    if (!MCostType.COSTINGMETHOD_AverageInvoice.equals(type.getCostingMethod())) continue;
                    for (MCostElement element : elements) {
                        boolean relevant = allocations.stream().anyMatch(a -> a.getM_Product_ID()==product
                                && a.getM_CostElement_ID()==element.get_ID());
                        if (!relevant) continue;
                        try (PreparedStatement statement = connection.prepareStatement("SELECT M_Product_ID FROM M_Cost"
                                + " WHERE AD_Client_ID=? AND M_Product_ID=? AND C_AcctSchema_ID=?"
                                + " AND M_CostType_ID=? AND M_CostElement_ID=?"
                                + " ORDER BY AD_Org_ID, M_Warehouse_ID, M_AttributeSetInstance_ID FOR UPDATE")) {
                            statement.setInt(1, getAD_Client_ID()); statement.setInt(2, product);
                            statement.setInt(3, schema.get_ID()); statement.setInt(4, type.get_ID());
                            statement.setInt(5, element.get_ID());
                            try (ResultSet result = statement.executeQuery()) {
                                if (!result.next()) throw new IllegalStateException("Missing landed cost dimension");
                            }
                        }
                    }
                }
    }

    private void generateIndependentLandedCost(MLandedCostAllocation allocation) {
        MTransaction transaction = validateIndependentLandedCost(allocation);
        for (MAcctSchema schema : acctSchemas)
            for (MCostType type : costTypes) {
                if (!MCostType.COSTINGMETHOD_AverageInvoice.equals(type.getCostingMethod())) continue;
                for (MCostElement element : costElements) {
                    if (element.get_ID() != allocation.getM_CostElement_ID()) continue;
                    int existing = new Query(getCtx(), MCostDetail.Table_Name,
                            "AD_Client_ID=? AND C_AcctSchema_ID=? AND M_CostType_ID=?"
                            + " AND M_CostElement_ID=? AND C_LandedCostAllocation_ID=?", get_TrxName())
                            .setParameters(getAD_Client_ID(), schema.get_ID(), type.get_ID(), element.get_ID(), allocation.get_ID()).count();
                    if (existing > 1) throw new IllegalStateException("Duplicate landed cost details require reconciliation");
                    if (existing == 1) continue; // Completed source identity already accounted for.
                    if (MCostType.get(schema, allocation.getM_Product_ID(), allocation.getAD_Org_ID()).get_ID()==type.get_ID()
                            && !CostEngineFactory.getCostEngine(getAD_Client_ID()).clearAccounting(schema, type,
                                    allocation.getInvoice(), allocation.getM_Product_ID(), allocation.getDateAcct()))
                        throw new IllegalStateException("Cannot clear accounting of the landed cost invoice");
                    CostEngineFactory.getCostEngine(getAD_Client_ID()).createCostDetail(schema, type, element,
                            transaction, allocation, true);
                }
            }
    }

    public void generateCostDetail(MAcctSchema accountSchema, MCostType costType, MCostElement costElement, MTransaction transaction) {

        //Create Cost Detail for this Transaction
        CostEngineFactory.getCostEngine(accountSchema.getAD_Client_ID()).createCostDetail(accountSchema, costType, costElement, transaction, transaction.getDocumentLine(), true);
        CostEngineFactory.getCostEngine(accountSchema.getAD_Client_ID()).clearAccounting(accountSchema, transaction);

        // Calculate adjustment cost by variances in
        // invoices
        if (MTransaction.MOVEMENTTYPE_VendorReceipts.equals(transaction.getMovementType())) {
            MInOutLine line = (MInOutLine) transaction.getDocumentLine();
            if (MCostElement.COSTELEMENTTYPE_Material.equals(costElement.getCostElementType())) {
                //get purchase matches
                List<MMatchPO> orderMatches = MMatchPO.getInOutLine(line);
                orderMatches.stream().forEach(match -> {
                    if (match.getM_Product_ID() == transaction.getM_Product_ID()
                    && isInDateRange(match.getDateAcct())) {
                        CostEngineFactory.getCostEngine(accountSchema.getAD_Client_ID()).createCostDetail(accountSchema, costType, costElement, transaction, match, true);
                    }
                });
                //get invoice matches
               List<MMatchInv> invoiceMatches = MMatchInv.getInOutLine(line);
               invoiceMatches.forEach(match -> {
                    if (match.getM_Product_ID() == transaction.getM_Product_ID()
                    && isInDateRange(match.getDateAcct())) {
                        CostEngineFactory.getCostEngine(
                                accountSchema.getAD_Client_ID())
                                .createCostDetail(accountSchema, costType, costElement, transaction,
                                        match, true);
                    }
                });
            }

            //get landed allocation cost
            MLandedCostAllocation.getOfInOutline(line, costElement.getM_CostElement_ID()).stream().forEach(allocation -> {
                if (isInDateRange(allocation.getDateAcct()))
                    CostEngineFactory.getCostEngine(accountSchema.getAD_Client_ID()).createCostDetail(accountSchema, costType, costElement, transaction, allocation, true);
            });
        }
    }

    private void generateCostCollectorNotTransaction(MAcctSchema accountSchema , MCostType costType ,  int productId, String trxName)
            throws SQLException {
        List<MPPCostCollector> costCollectors = StandardCostCollector.getCostCollectorNotTransaction(getCtx(), productId, getDateAcct(), getDateAcctTo(), trxName);
        // Process Collector Cost Manufacturing
        for (MPPCostCollector costCollector : costCollectors) {
            for (MCostDetail costDetail : StandardCostCollector.getByCollectorCost(costCollector)) {
                costDetail.deleteEx(true);
            }
            CostEngineFactory.getCostEngine(getAD_Client_ID()).clearAccounting(accountSchema, costType, costCollector, productId , costCollector.getDateAcct());
            if (MPPCostCollector.COSTCOLLECTORTYPE_UsegeVariance.equals(costCollector.getCostCollectorType()))
            	StandardCostCollector.createUsageVariances(costCollector);
            else if (MPPCostCollector.COSTCOLLECTORTYPE_MethodChangeVariance.equals(costCollector.getCostCollectorType()))
            	StandardCostCollector.createMethodVariancesFromActivityControl(costCollector);
            else if (MPPCostCollector.COSTCOLLECTORTYPE_RateVariance.equals(costCollector.getCostCollectorType()))
            	StandardCostCollector.createRateVariances(costCollector);
            else if (MPPCostCollector.COSTCOLLECTORTYPE_ActivityControl.equals(costCollector.getCostCollectorType()))
            	StandardCostCollector.createActivityControl(costCollector);
            //else
                //System.out.println("Cost Collector Type: " + costCollector.getCostCollectorType());
        }
    }

    private KeyNamePair[] getTransactionIdsByDateAcct() {
        StringBuilder sql = new StringBuilder();
        List<Object> parameters = new ArrayList<Object>();
        StringBuilder whereClause = new StringBuilder("WHERE ");
        whereClause.append(MCostDetail.COLUMNNAME_AD_Client_ID).append("=")
                .append(getAD_Client_ID()).append(" AND ");
        if (getProductId() > 0) {
            whereClause.append(MCostDetail.COLUMNNAME_M_Product_ID)
                    .append("=?").append(" AND ");
            parameters.add(getProductId());
        }
        
        if (getProductCategoryId() > 0) {
            whereClause.append("M_Product_Category_ID")
                    .append("=?").append(" AND ");
            parameters.add(getProductCategoryId());
        }
        whereClause.append("TRUNC(").append(MCostDetail.COLUMNNAME_DateAcct).append(")>=?");
        parameters.add(getDateAcct());

        if (getDateAcctTo() != null) {
            whereClause.append(" AND TRUNC(").append(MCostDetail.COLUMNNAME_DateAcct).append(")<=?");
            parameters.add(getDateAcctTo());
        }

        sql.append("SELECT M_Transaction_ID , M_Product_ID FROM RV_Transaction ")
                .append(whereClause)
                .append(" ORDER BY lowlevel desc, M_Product_ID ,  TRUNC( DateAcct ) , M_Transaction_ID , SUBSTR(MovementType,2,1) ");
        //.append(" ORDER BY M_Product_ID , DateAcct , M_Transaction_ID");
        //System.out.append("SQL :" + sql);
        return DB.getKeyNamePairs(get_TrxName(), sql.toString(), false, parameters.toArray());
    }
    
    
}
