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
 * Copyright (C) 2003-2007 e-Evolution,SC. All Rights Reserved.               *
 * Contributor(s): victor.perez@e-evolution.com http://www.e-evolution.com    *
 *****************************************************************************/

package org.adempiere.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.adempiere.exceptions.ZeroSalesCostException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import org.adempiere.core.domains.models.I_C_InvoiceLine;
import org.adempiere.core.domains.models.I_C_OrderLine;
import org.adempiere.core.domains.models.I_C_ProjectIssue;
import org.adempiere.core.domains.models.I_M_CostElement;
import org.adempiere.core.domains.models.I_M_CostType;
import org.adempiere.core.domains.models.I_M_InOut;
import org.adempiere.core.domains.models.I_M_Inventory;
import org.adempiere.core.domains.models.I_M_MatchInv;
import org.adempiere.core.domains.models.I_M_MatchPO;
import org.adempiere.core.domains.models.I_M_Movement;
import org.adempiere.core.domains.models.I_M_Product;
import org.adempiere.core.domains.models.I_M_Production;
import org.adempiere.core.domains.models.I_M_Transaction;
import org.adempiere.core.domains.models.I_PP_Cost_Collector;
import org.adempiere.core.domains.models.X_M_Product;
import org.adempiere.core.domains.models.X_PP_Cost_Collector;
import org.compiere.model.MAcctSchema;
import org.compiere.model.MClient;
import org.compiere.model.MConversionRate;
import org.compiere.model.MConversionType;
import org.compiere.model.MCost;
import org.compiere.model.MCostDetail;
import org.compiere.model.MCostElement;
import org.compiere.model.MCostType;
import org.compiere.model.MDocType;
import org.compiere.model.MInOutLine;
import org.compiere.model.MInvoice;
import org.compiere.model.MInventoryLine;
import org.compiere.model.MLandedCostAllocation;
import org.compiere.model.MMatchInv;
import org.compiere.model.MMatchPO;
import org.compiere.model.MMovementLine;
import org.compiere.model.MPeriod;
import org.compiere.model.MPeriodControl;
import org.compiere.model.MProduct;
import org.compiere.model.MProductCategoryAcct;
import org.compiere.model.MProductPO;
import org.compiere.model.MProduction;
import org.compiere.model.MProductionLine;
import org.compiere.model.MProjectIssue;
import org.compiere.model.MTransaction;
import org.compiere.model.PO;
import org.compiere.model.Query;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Util;


/**
 * Cost Engine
 * 
 * @author victor.perez@e-evolution.com http://www.e-evolution.com
 * 
 */
public class CostEngine {
	/** Logger */
	protected transient CLogger log = CLogger.getCLogger(getClass());

    /** AD_Table_ID's of documents          */
    public final static int[]  documentsTableID = {
            I_M_InOut.Table_ID,
            I_M_Inventory.Table_ID,
            I_M_Movement.Table_ID,
            I_M_Product.Table_ID,
            I_C_ProjectIssue.Table_ID,
            I_PP_Cost_Collector.Table_ID,
            I_M_MatchPO.Table_ID,
            I_M_MatchInv.Table_ID};

    /** Table Names of documents          */
    public final static String[]  documentsTableName = {
            I_M_InOut.Table_Name,
            I_M_Inventory.Table_Name,
            I_M_Movement.Table_Name,
            I_M_Production.Table_Name,
            I_C_ProjectIssue.Table_Name,
            I_PP_Cost_Collector.Table_Name,
            I_M_MatchPO.Table_Name,
            I_M_MatchInv.Table_Name};

    /**
     * get seed cost
     * @param context
     * @param productId
     * @param trxName
     * @return
     */
	public static BigDecimal getSeedCost(Properties context , int  productId ,  String trxName)
	{
		BigDecimal costThisLevel = Env.ZERO;
		for (MProductPO productPO : MProductPO.getOfProduct(context,productId, trxName))
		 {
			 if (productPO.isCurrentVendor())
			 { 
				 if (productPO.getPriceLastInv().signum() != 0)
					 costThisLevel = productPO.getPriceLastInv();
				 else if (productPO.getPriceLastPO().signum() != 0) 
					 costThisLevel = productPO.getPriceLastPO();
				 else if  (productPO.getPricePO().signum() != 0) 
					 costThisLevel = productPO.getPricePO();
				 else 
					 costThisLevel = productPO.getPriceList();
				 return costThisLevel;
			 } 
		 }
		return costThisLevel;
	}

    /**
     *  Get Actual Cost of Parent Product Based on Cost Type
     * @param accountSchema
     * @param costTypeId
     * @param costElementId
     * @param costCollector
     * @return
     */
	public static BigDecimal getParentActualCostByCostType(MAcctSchema accountSchema, int costTypeId, int costElementId, X_PP_Cost_Collector costCollector) {
		StringBuffer whereClause = new StringBuffer()
		.append(MCostDetail.COLUMNNAME_C_AcctSchema_ID).append("=? AND ")
		.append(MCostDetail.COLUMNNAME_M_CostType_ID + "=? AND ")
		.append(MCostDetail.COLUMNNAME_M_CostElement_ID + "=? AND ")
		.append(MCostDetail.COLUMNNAME_PP_Cost_Collector_ID)
		.append(" IN (SELECT PP_Cost_Collector_ID FROM PP_Cost_Collector cc WHERE cc.PP_Order_ID=? AND ")
		.append(" cc.CostCollectorType <> '").append(X_PP_Cost_Collector.COSTCOLLECTORTYPE_MaterialReceipt).append("')");

		List<MCostDetail> componentsIssue= new Query(costCollector.getCtx(), MCostDetail.Table_Name, whereClause.toString(), costCollector.get_TrxName())
				.setClient_ID()
				.setParameters(accountSchema.getC_AcctSchema_ID() , costTypeId, costElementId, costCollector.getPP_Order_ID())
				.list();

		AtomicReference<BigDecimal> actualCostReference = new AtomicReference(BigDecimal.ZERO);
		componentsIssue.stream().forEach( costDetail ->{
			if (costDetail.getQty().signum() < 0)
				actualCostReference.updateAndGet(cost -> cost.subtract(costDetail.getAmt().add(costDetail.getAmtLL())));
			else
				actualCostReference.updateAndGet(cost -> cost.add(costDetail.getAmt().add(costDetail.getAmtLL())));

		});

		BigDecimal actualCost = actualCostReference.get();
		whereClause = new StringBuffer();
		whereClause
				.append(" EXISTS (SELECT 1 FROM PP_Cost_Collector cc ")
				.append(" WHERE PP_Cost_Collector_ID=M_Transaction.PP_Cost_Collector_ID AND cc.PP_Order_ID=? AND cc.M_Product_ID=? )");
		BigDecimal qtyDelivered = new Query(costCollector.getCtx(), I_M_Transaction.Table_Name, whereClause.toString(), costCollector.get_TrxName())
				.setClient_ID()
				.setParameters(costCollector.getPP_Order_ID(), costCollector.getM_Product_ID())
				.sum(MTransaction.COLUMNNAME_MovementQty);

		if (actualCost == null)
			actualCost = Env.ZERO;

		if (qtyDelivered.signum() != 0)
			actualCost = actualCost.divide(qtyDelivered,
					accountSchema.getCostingPrecision(), RoundingMode.HALF_DOWN);
		int conversionTypeId = MConversionType.getDefault(costCollector.getAD_Client_ID());
		MClient client  = MClient.get(costCollector.getCtx());
		int currencyId = client.getC_Currency_ID();
		BigDecimal rate = MConversionRate.getRate(
				currencyId, currencyId,
				costCollector.getDateAcct(), conversionTypeId,
				costCollector.getAD_Client_ID(), costCollector.getAD_Org_ID());
		if (rate != null) {
			actualCost = actualCost.multiply(rate);
			if (actualCost.scale() > accountSchema.getCostingPrecision())
				actualCost = actualCost.setScale(accountSchema.getCostingPrecision(), RoundingMode.HALF_UP);
		}

		return actualCost;
	}

	public static BigDecimal getParentActualCostByCostType(MAcctSchema accountSchema, MCostType costType,
			MCostElement costElement, I_M_Production production) {
		//	Get BOM Cost - Sum of individual lines
		BigDecimal totalCost = Env.ZERO;
		for (MProductionLine productionLine : ((MProduction)production).getLines())
		{
			if (productionLine.isParent())
				continue;

			String productType = productionLine.getM_Product().getProductType();

			BigDecimal cost = BigDecimal.ZERO;

			if (X_M_Product.PRODUCTTYPE_Item.equals(productType)) {
				cost = MCostDetail.getCostByModel(accountSchema.getC_AcctSchema_ID(), costType.getM_CostType_ID() , costElement.getM_CostElement_ID() , productionLine);
			}
			else if(X_M_Product.PRODUCTTYPE_Resource.equals(productType))
			{
				MCost costDimension = MCost.validateCostForCostType(accountSchema, costType, costElement,
						productionLine.getM_Product_ID(), productionLine.getAD_Org_ID(), productionLine.getM_Locator().getM_Warehouse_ID(),
						productionLine.getM_AttributeSetInstance_ID(), productionLine.get_TrxName());

				if (costDimension != null && costDimension.getCurrentCostPrice().signum() != 0 )
					cost = costDimension.getCurrentCostPrice().multiply(productionLine.getMovementQty().negate());
			}


			if (cost != null && cost.signum() != 0)
				totalCost = totalCost.add(cost);

		}

		BigDecimal unitCost = Env.ZERO;
		if (production.getProductionQty().signum() != 0 && totalCost.signum() != 0)
			unitCost = totalCost.divide(production.getProductionQty(), accountSchema.getCostingPrecision(), RoundingMode.HALF_UP);

		return unitCost;
	}

	public static BigDecimal roundCost(BigDecimal price, int accountSchemaId) {
		// Fix Cost Precision
		int precision = MAcctSchema.get(Env.getCtx(), accountSchemaId)
				.getCostingPrecision();
		BigDecimal priceRounded = price;
		if (priceRounded.scale() > precision) {
			priceRounded = priceRounded.setScale(precision,
					RoundingMode.HALF_UP);
		}
		return priceRounded;
	}

	/**
	 * Generate by transaction
	 * @param transaction
	 */
	public void createCostDetail(MTransaction transaction, IDocumentLine model) {

		MClient client =  MClient.get(transaction.getCtx());
		StringBuilder description = new StringBuilder();
		if (model != null && model.getDescription() != null && !Util.isEmpty(model.getDescription(), true))
			description.append(model.getDescription());
		if (model != null) {
			description.append(model.isSOTrx() ? "(|->)" : "(|<-)");
		}

		List<MAcctSchema> acctSchemas = new ArrayList(Arrays.asList(MAcctSchema
				.getClientAcctSchema(transaction.getCtx(), transaction.getAD_Client_ID(),
						transaction.get_TrxName())));

		List<MCostElement> costElements = MCostElement.getCostElement(transaction.getCtx(),
				transaction.get_TrxName());
		List<MCostType> costTypes = MCostType.get(transaction.getCtx(), transaction.get_TrxName());
		for (MAcctSchema accountSchema : acctSchemas) {
			for (MCostType costType : costTypes) {
				if (!costType.isActive())
					continue;
				for (MCostElement costElement : costElements) {
					createCostDetail(accountSchema, costType, costElement, transaction, model, client.isCostImmediate());
				}
			}
            if (client.isCostImmediate())
                validateSalesCost(accountSchema, transaction, model);
		}
	}


    /** Validate a completed set of components, never an individual parallel cost element. */
    public static void validateSalesCost(MAcctSchema schema, MTransaction transaction, IDocumentLine model) {
        if (!(model instanceof MInOutLine) || !model.isSOTrx() || model.getReversalLine_ID() > 0
                || !MTransaction.MOVEMENTTYPE_CustomerShipment.equals(transaction.getMovementType())
                || transaction.getMovementQty().signum() == 0)
            return;
        MInOutLine line = (MInOutLine) model;
        if (!line.getM_Product().isStocked())
            return;
        MCostType type = MCostType.get(schema, transaction.getM_Product_ID(), transaction.getAD_Org_ID());
        List<MCostDetail> details = new Query(transaction.getCtx(), MCostDetail.Table_Name,
                "AD_Client_ID=? AND C_AcctSchema_ID=? AND M_Product_ID=? AND M_CostType_ID=? AND M_Transaction_ID=?",
                transaction.get_TrxName()).setParameters(transaction.getAD_Client_ID(), schema.get_ID(),
                        transaction.getM_Product_ID(), type.get_ID(), transaction.get_ID()).list();
        requireSalesCost(schema, line.getM_Product_ID(), line.get_ID(), type.get_ID(), line.getDateAcct(), details);
    }

    /** The caller supplies only the cost details of this line and its accounting cost type. */
    public static void requireSalesCost(MAcctSchema schema, int productId, int lineId, int costTypeId,
            Timestamp dateAcct, List<MCostDetail> details) {
        BigDecimal total = Env.ZERO;
        for (MCostDetail detail : details)
            total = total.add(MCostDetail.getTotalCost(detail, schema));
        if (total.signum() == 0)
            throw new ZeroSalesCostException(productId, lineId, schema.get_ID(), costTypeId, dateAcct);
    }

	/**
	 * Create Cost Detail
	 * @param accountSchema Account Schema
	 * @param transaction Material Transaction
	 * @param model IDocumentLine
	 * @param costType Cost Type
	 * @param costElement Cost Element
	 */
	public void createCostDetail(MAcctSchema accountSchema,MCostType costType, MCostElement costElement, MTransaction transaction , IDocumentLine model, boolean force) {

		if (!force)
			return;
        // The physical vendor return already removed inventory. Its invoice match
        // settles the credit; any price difference belongs to MatchInv accounting.
        if (model instanceof MMatchInv
                && MCostType.COSTINGMETHOD_AverageInvoice.equals(costType.getCostingMethod())
                && MTransaction.MOVEMENTTYPE_VendorReturns.equals(transaction.getMovementType()))
            return;

            if (model instanceof MInOutLine
                    && MCostType.COSTINGMETHOD_AverageInvoice.equals(costType.getCostingMethod())) {
                MDocType documentType = MDocType.get(model.getCtx(), model.getC_DocType_ID());
                if (!MPeriod.isOpen(model.getCtx(), model.getDateAcct(), documentType.getDocBaseType(),
                        transaction.getAD_Org_ID(), transaction.get_TrxName()))
                    return; // Historical physical costs in closed periods are immutable.
            }
            boolean parallelStandardLandedCost = model instanceof MLandedCostAllocation
                    && MCostType.COSTINGMETHOD_StandardCosting.equals(costType.getCostingMethod())
                    && costType.get_ID() != MCostType.get(accountSchema, transaction.getM_Product_ID(),
                            transaction.getAD_Org_ID()).get_ID();
            if ((model instanceof MMatchPO || (model instanceof MMatchInv
                    && MCostType.COSTINGMETHOD_StandardCosting.equals(costType.getCostingMethod()))
                    || parallelStandardLandedCost) && transaction.getM_InOutLine_ID() > 0) {
                MInOutLine receipt = (MInOutLine) transaction.getM_InOutLine();
                MDocType documentType = MDocType.get(receipt.getCtx(), receipt.getC_DocType_ID());
                if (!MPeriod.isOpen(receipt.getCtx(), receipt.getDateAcct(), documentType.getDocBaseType(),
                        transaction.getAD_Org_ID(), transaction.get_TrxName()))
                    return; // A late invoice must not rewrite the original receipt provision.
            }
		
		BigDecimal costThisLevel = Env.ZERO;
		BigDecimal costLowLevel = Env.ZERO;
		String costingLevel = MProduct.get(transaction.getCtx(),
				transaction.getM_Product_ID()).getCostingLevel(accountSchema,
				transaction.getAD_Org_ID());
		
		// The Change of price in the Invoice Line is not generated cost
		// adjustment for Average PO Costing method
		if (model instanceof MMatchInv
				&& MCostType.COSTINGMETHOD_AveragePO.equals(costType
                .getCostingMethod()))
			return;

		// The Change of price in the Invoice Line is not generated cost
		// adjustment for Average PO Costing method
		if (model instanceof MMatchPO
				&& MCostType.COSTINGMETHOD_AverageInvoice.equals(costType
						.getCostingMethod()))
			return;

		if (model instanceof MLandedCostAllocation) {
			MLandedCostAllocation allocation = (MLandedCostAllocation) model;
            costThisLevel = MCostType.COSTINGMETHOD_AverageInvoice.equals(costType.getCostingMethod())
                    ? getLandedCostAmount(accountSchema, allocation).divide(allocation.getQty(),
                            accountSchema.getCostingPrecision(), RoundingMode.HALF_UP)
                    : allocation.getPriceActual();
		}

        if (model instanceof MLandedCostAllocation
                && MCostType.COSTINGMETHOD_AverageInvoice.equals(costType.getCostingMethod())
                && transaction.get_TrxName() == null)
            throw new IllegalStateException("Landed-cost serialization requires a caller transaction");

		MCost cost = MCost.validateCostForCostType(accountSchema, costType, costElement,
				transaction.getM_Product_ID(), transaction.getAD_Org_ID(), transaction.getM_Warehouse_ID(),
				transaction.getM_AttributeSetInstance_ID(), transaction.get_TrxName());

        if (model instanceof MLandedCostAllocation
                && MCostType.COSTINGMETHOD_AverageInvoice.equals(costType.getCostingMethod())) {
            // Serialize this dimension before looking up the allocation detail or
            // calculating its basis. Reload after waiting for another transaction.
            DB.getSQLValueEx(transaction.get_TrxName(),
                    "SELECT M_Product_ID FROM M_Cost WHERE AD_Client_ID=? AND M_Product_ID=?"
                    + " AND C_AcctSchema_ID=? AND M_CostType_ID=? AND M_CostElement_ID=?"
                    + " AND AD_Org_ID=? AND M_Warehouse_ID=? AND M_AttributeSetInstance_ID=? FOR UPDATE",
                    cost.getAD_Client_ID(), cost.getM_Product_ID(), cost.getC_AcctSchema_ID(),
                    cost.getM_CostType_ID(), cost.getM_CostElement_ID(), cost.getAD_Org_ID(),
                    cost.getM_Warehouse_ID(), cost.getM_AttributeSetInstance_ID());
            if (!cost.load(transaction.get_TrxName()))
                throw new IllegalStateException("Missing locked landed-cost dimension");
        }

		// get the cost for positive transaction
		if ((MCostElement.COSTELEMENTTYPE_Material.equals(costElement
				.getCostElementType()) || MCostElement.COSTELEMENTTYPE_LandedCost
				.equals(costElement.getCostElementType()))
				&& transaction.getMovementType().contains("+")
				&& !MCostType.COSTINGMETHOD_StandardCosting.equals(costType
						.getCostingMethod())) {
			if 
			(	model instanceof MMovementLine
			||  model instanceof MInventoryLine
			|| (model instanceof MInOutLine && MTransaction.MOVEMENTTYPE_CustomerReturns.equals(transaction.getMovementType()))
			) 
			{				
				costThisLevel = getCostThisLevel(accountSchema, costType, costElement, transaction, model, costingLevel);
				costLowLevel = getCostLowLevel(accountSchema, costType, costElement, transaction, model, costingLevel);
				if (model instanceof MInventoryLine) {
					MInventoryLine inventoryLine = (MInventoryLine) model;
					// If cost this level is zero and is a physical inventory then
					// try get cost from physical inventory
					if (costThisLevel.signum() == 0 && MCostElement.COSTELEMENTTYPE_Material.equals(costElement.getCostElementType())) {
						// Use the current cost only for Physical Inventory
						if (inventoryLine.getQtyInternalUse().signum() == 0 &&
								inventoryLine.getCurrentCostPrice() != null &&
								inventoryLine.getCurrentCostPrice().signum() > 0) {
							costThisLevel = convertCostToSchemaCurrency(accountSchema, model, model.getPriceActualCurrency());
						}
						if (costThisLevel.signum() == 0)
							costThisLevel = getCostThisLevel(accountSchema, costType, costElement, transaction, model, costingLevel);
					}

					// If cost Low level is zero and is a physical inventory then
					// try get cost low level from physical inventory
					if (costLowLevel.signum() == 0 && MCostElement.COSTELEMENTTYPE_Material.equals(costElement.getCostElementType())) {
						// Use the current cost only for Physical Inventory
						if (inventoryLine.getQtyInternalUse().signum() == 0 &&
								inventoryLine.getCurrentCostPriceLL() != null &&
								inventoryLine.getCurrentCostPriceLL().signum() > 0) {
							costLowLevel = convertCostToSchemaCurrency(accountSchema, model, inventoryLine.getCurrentCostPriceLL());
						}
						if (costLowLevel.signum() == 0)
							costLowLevel = getCostLowLevel(accountSchema, costType, costElement, transaction, model, costingLevel);
					}
				}


				//Get cost from movement from if it > that zero replace cost This Level
				if (model instanceof MMovementLine) {
					MTransaction transactionFrom = MTransaction.getByDocumentLine(model, MTransaction.MOVEMENTTYPE_MovementFrom);
					BigDecimal costMovementFrom = getCostThisLevel(accountSchema, costType, costElement, transactionFrom == null ? transaction : transactionFrom, model,costingLevel);
					if (costMovementFrom.signum() > 0 )
						costThisLevel = costMovementFrom;

					BigDecimal costMovementFromLL = getCostLowLevel(accountSchema, costType, costElement, transactionFrom == null ? transaction : transactionFrom, model,costingLevel);
					if (costMovementFromLL.signum() > 0 )
						costLowLevel = costMovementFromLL;
				}
			} else if (MCostElement.COSTELEMENTTYPE_Material.equals(costElement.getCostElementType())) {
					costThisLevel = convertCostToSchemaCurrency(accountSchema , model , model.getPriceActualCurrency());
			}
		}

		if (!MCostType.COSTINGMETHOD_StandardCosting.equals(costType.getCostingMethod())) {
			if(model.get_TableName().equals(I_PP_Cost_Collector.Table_Name)) {
				X_PP_Cost_Collector costCollector = (X_PP_Cost_Collector) model;
				if (X_PP_Cost_Collector.COSTCOLLECTORTYPE_MaterialReceipt.equals(costCollector.getCostCollectorType())) {
					// get Actual Cost for Cost Type and Cost Element
					costThisLevel = getCostThisLevel(accountSchema, costType, costElement, transaction, model, costingLevel);
					costLowLevel = CostEngine.getParentActualCostByCostType(accountSchema, costType.getM_CostType_ID(), costElement.getM_CostElement_ID(), costCollector);
				} 
			}
			if (model instanceof MProductionLine) {
				
				MProductionLine productionLine = (MProductionLine) model;
				if (productionLine.isParent())
						costThisLevel = CostEngine.getParentActualCostByCostType(
								accountSchema, costType, costElement, productionLine.getM_Production());
				
				if(costThisLevel.signum() == 0)
					costThisLevel = cost.getCurrentCostPrice();
					if(costThisLevel.signum() == 0 
					&& MCostElement.COSTELEMENTTYPE_Material.equals(costElement
							.getCostElementType()))
						costThisLevel = getSeedCost(
                                transaction.getCtx(),
                                transaction.getM_Product_ID(),
                                transaction.get_TrxName());
						
				// Material Receipt for Production light
				if (productionLine.isParent()) {
					// get Actual Cost for Cost Type and Cost Element
					// if the product is purchase then no use low level 
					if (!productionLine.getM_Product().isPurchased())
					{	
						costLowLevel  = costThisLevel;
						costThisLevel = Env.ZERO;
					} 
				} else if ( productionLine.getMovementQty().signum() < 0)
					costLowLevel= Env.ZERO;
			}
		}
        else if (MCostType.COSTINGMETHOD_StandardCosting.equals(costType.getCostingMethod())){
			costThisLevel = cost.getCurrentCostPrice();
			costLowLevel = cost.getCurrentCostPriceLL();
			//Define Cost Inventory Line
			if (model instanceof MInventoryLine) {
				MInventoryLine inventoryLine = (MInventoryLine) model;
				//Define Current Cost Level
				if (costThisLevel.signum() == 0 && MCostElement.COSTELEMENTTYPE_Material.equals(costElement.getCostElementType())) {
					// Use the current cost only for Physical Inventory
					if (inventoryLine.getQtyInternalUse().signum() == 0 &&
							inventoryLine.getCurrentCostPrice() != null &&
							inventoryLine.getCurrentCostPrice().signum() > 0) {
						costThisLevel = convertCostToSchemaCurrency(accountSchema, model, model.getPriceActualCurrency());
						cost.setCurrentCostPrice(costThisLevel);
						cost.saveEx();
					}

					if (costThisLevel.signum() == 0)
						costThisLevel = getCostThisLevel(accountSchema, costType, costElement, transaction, model, costingLevel);
				}
				//Define Current Cost Low Level
				if (costLowLevel.signum() == 0 && MCostElement.COSTELEMENTTYPE_Material.equals(costElement.getCostElementType())) {
					// Use the cost only for Physical Inventory
					if (inventoryLine.getQtyInternalUse().signum() == 0 &&
							inventoryLine.getCurrentCostPriceLL() != null &&
							inventoryLine.getCurrentCostPriceLL().signum() > 0) {
						costLowLevel = convertCostToSchemaCurrency(accountSchema, model, inventoryLine.getCurrentCostPriceLL());

						cost.setCurrentCostPriceLL(costLowLevel);
						cost.saveEx();
					}

					if (costLowLevel.signum() == 0)
						costLowLevel = getCostLowLevel(accountSchema, costType, costElement, transaction, model, costingLevel);
				}
			}

			if (model instanceof  MMovementLine) {
				MTransaction transactionFrom = MTransaction.getByDocumentLine(model, MTransaction.MOVEMENTTYPE_MovementFrom);
				BigDecimal costMovementFrom = getCostThisLevel(accountSchema, costType, costElement, transactionFrom == null ? transaction : transactionFrom, model, costingLevel);
				if (costThisLevel.signum() == 0 && MCostElement.COSTELEMENTTYPE_Material.equals(costElement.getCostElementType())) {
					if (costMovementFrom.signum() > 0)
						costThisLevel = costMovementFrom;
				}
				if (costLowLevel.signum() == 0 && MCostElement.COSTELEMENTTYPE_Material.equals(costElement.getCostElementType())) {
					BigDecimal costMovementFromLL = getCostLowLevel(accountSchema, costType, costElement, transactionFrom == null ? transaction : transactionFrom, model, costingLevel);
					if (costMovementFromLL.signum() > 0)
						costLowLevel = costMovementFromLL;
				}
			}

            if (costThisLevel.signum() == 0 &&  MCostElement.COSTELEMENTTYPE_Material.equals(costElement.getCostElementType())) {
                costThisLevel = getSeedCost(transaction.getCtx(), transaction.getM_Product_ID(), transaction.get_TrxName());
                if (costThisLevel.signum() == 0)
                    if (model instanceof  MInOutLine && !model.isSOTrx()) {
							costThisLevel = convertCostToSchemaCurrency(accountSchema , model , model.getPriceActualCurrency());
                    }
                if (costThisLevel.signum() != 0) {
                    cost.setCurrentCostPrice(costThisLevel);
                    cost.saveEx();
                }
            }

			if (costLowLevel.signum() != 0) {
				cost.setCurrentCostPriceLL(costLowLevel);
				cost.saveEx();
			}
        }

		final ICostingMethod method = CostingMethodFactory.get()
				.getCostingMethod(costType.getCostingMethod());
		method.setCostingMethod(accountSchema, transaction, model, cost, costThisLevel,
				costLowLevel, model.isSOTrx());
        method.process();
	}


	/**
	 * Convert Cost To Schema Currency
	 * @param acctSchema
	 * @param model
	 * @param cost
	 * @return
	 */
	private BigDecimal convertCostToSchemaCurrency(MAcctSchema acctSchema , IDocumentLine model , BigDecimal cost)
	{
		BigDecimal totalCostThisLevel = MConversionRate.convertBase(
				model.getCtx(),
				cost,
				model.getC_Currency_ID(),
				model.getDateAcct(),
				model.getC_ConversionType_ID(),
				model.getAD_Client_ID(),
				model.getAD_Org_ID());
		return totalCostThisLevel;
	}


    /** Convert the allocation total once, before deriving any per-unit amounts. */
    public static BigDecimal getLandedCostAmount(MAcctSchema schema, MLandedCostAllocation allocation) {
        BigDecimal amount = MConversionRate.convert(allocation.getCtx(), allocation.getAmt(),
                allocation.getC_Currency_ID(), schema.getC_Currency_ID(), allocation.getDateAcct(),
                allocation.getC_ConversionType_ID(), allocation.getAD_Client_ID(), allocation.getAD_Org_ID());
        if (amount == null)
            throw new IllegalStateException("Missing currency conversion for landed cost " + allocation.get_ID());
        MInvoice invoice = allocation.getInvoice();
        if (MDocType.DOCBASETYPE_APCreditMemo.equals(invoice.getC_DocType().getDocBaseType()))
            amount = amount.negate();
        return amount;
    }

	//Create cost detail for by document
	public void createCostDetailForLandedCostAllocation(
			MLandedCostAllocation allocation) {
		MInOutLine ioLine = (MInOutLine) allocation.getM_InOutLine();
		
		for (MTransaction transaction : MTransaction.getByInOutLine(ioLine)) {
			for (MAcctSchema accountSchema : MAcctSchema.getClientAcctSchema(
					allocation.getCtx(), allocation.getAD_Client_ID())) {
				List<MCostType> costTypes = MCostType.get(allocation.getCtx(),
						allocation.get_TrxName());

				for (MCostType costType : costTypes) {
					MCostElement costElement = (MCostElement) allocation
							.getM_CostElement();
					CostEngineFactory.getCostEngine(
							allocation.getAD_Client_ID()).createCostDetail(accountSchema, costType, costElement, transaction, allocation, true);
				}
			}
		}
	}

	public static boolean isActivityControlElement(I_M_CostElement element) {
		String costElementType = element.getCostElementType();
		return MCostElement.COSTELEMENTTYPE_Resource.equals(costElementType)
				|| MCostElement.COSTELEMENTTYPE_Overhead.equals(costElementType)
				|| MCostElement.COSTELEMENTTYPE_BurdenMOverhead.equals(costElementType)
				|| MCostElement.COSTELEMENTTYPE_OutsideProcessing.equals(costElementType);
	}

	public static List<MAcctSchema> getAcctSchema(PO po) {
		int AD_Org_ID = po.getAD_Org_ID();
		MAcctSchema[] ass = MAcctSchema.getClientAcctSchema(po.getCtx(),
				po.getAD_Client_ID());
		ArrayList<MAcctSchema> list = new ArrayList<MAcctSchema>(ass.length);
		for (MAcctSchema as : ass) {
			if (!as.isSkipOrg(AD_Org_ID))
				list.add(as);
		}
		return list;
	}

	static public String getIDColumnName(IDocumentLine model) {
		String idColumnName = model.get_TableName() + "_ID";
		if (model instanceof MMatchPO) {
			idColumnName = I_C_OrderLine.COLUMNNAME_C_OrderLine_ID;
		}
		if (model instanceof MMatchInv) {
			idColumnName = I_C_InvoiceLine.COLUMNNAME_C_InvoiceLine_ID;
		}
		return idColumnName;
	}

	static public int getIDColumn(IDocumentLine model) {
		int id = model.get_ID();

		if (model instanceof MMatchPO) {
			id = ((MMatchPO) model).getC_OrderLine_ID();
		}
		if (model instanceof MMatchInv) {
			id = ((MMatchInv) model).getC_InvoiceLine_ID();
		}
		return id;
	}


    /**
     * get cost this level
     * @param accountSchema
     * @param costType
     * @param costElement
     * @param transaction
     * @param model
     * @param costingLevel
     * @return
     */
	public static BigDecimal getCostThisLevel(MAcctSchema accountSchema, I_M_CostType costType, I_M_CostElement costElement, MTransaction transaction,
                                              IDocumentLine model,
                                              String costingLevel) {
		BigDecimal costThisLevel = Env.ZERO;
		MCostDetail lastCostDetail = MCostDetail.getLastTransaction(model,
                transaction, accountSchema.getC_AcctSchema_ID(), costType.getM_CostType_ID(),
                costElement.getM_CostElement_ID(), model.getDateAcct(), costingLevel);
		if (lastCostDetail != null) {
			
				// Return of unit cost from last transaction 
				// transaction quantity is different of zero
				// then cost this level is equal that:
				// (Total Cost transaction + cost adjustments) divide by transaction quantity
				if (lastCostDetail.getQty().signum() != 0)
				{
					costThisLevel =  lastCostDetail.getCostAmt().add(
							lastCostDetail.getCostAdjustment())
							.divide(lastCostDetail.getQty(), accountSchema.getCostingPrecision(), RoundingMode.HALF_UP)
							.abs();
				}
				// return unit cost from last transaction
				// transaction quantity is zero
				// the cost this level is equal that:
				// (Total Cost Transaction + cost adjustments + accumulate cost) divide between on hand quantity 
				else if (lastCostDetail.getCumulatedQty().add( lastCostDetail.getQty()).signum() != 0)
				{
					costThisLevel =  lastCostDetail.getCostAmt().add(
									 lastCostDetail.getCostAdjustment()).add(
									 lastCostDetail.getCumulatedAmt())
							.divide(
									lastCostDetail.getCumulatedQty().add( lastCostDetail.getQty()),
									accountSchema.getCostingPrecision(),
									RoundingMode.HALF_UP
							)
							.abs();
					
					return costThisLevel;
				}	
				// Return of unit cost from inventory value
				// Cumulated quantity is different of zero 
				// then cost this level is equal that:
				// (Total Cost transaction + cost adjustments + Cumulated amount) divide by On hand Quantity
				else if (lastCostDetail.getCumulatedQty().signum() != 0)
				{
					costThisLevel = lastCostDetail.getCumulatedAmt()
							.divide(
									lastCostDetail.getCumulatedQty(),
									accountSchema.getCostingPrecision(),
									RoundingMode.HALF_UP
							)
							.abs();

					return costThisLevel;
				}	
				
			}


        // Preserve a recorded unit estimate after a zero-quantity, zero-value event.
        if (lastCostDetail != null && lastCostDetail.getQty().signum() == 0
                && lastCostDetail.getCumulatedQty().signum() == 0
                && lastCostDetail.getCumulatedAmt().add(lastCostDetail.getCostAmt())
                        .add(lastCostDetail.getCostAdjustment()).signum() == 0)
            costThisLevel = lastCostDetail.getCurrentCostPrice().abs();


		return costThisLevel;
	}

	/**
	 * get cost this level
	 * @param accountSchema
	 * @param costType
	 * @param costElement
	 * @param transaction
	 * @param model
	 * @param costingLevel
	 * @return
	 */
	public static BigDecimal getCostLowLevel(MAcctSchema accountSchema, I_M_CostType costType, I_M_CostElement costElement, MTransaction transaction,
											  IDocumentLine model,
											  String costingLevel) {
		BigDecimal costLowLevel = Env.ZERO;
		MCostDetail lastCostDetail = MCostDetail.getLastTransaction(model,
				transaction, accountSchema.getC_AcctSchema_ID(), costType.getM_CostType_ID(),
				costElement.getM_CostElement_ID(), model.getDateAcct(), costingLevel);
		if (lastCostDetail != null) {

			// Return of unit cost from last transaction
			// transaction quantity is different of zero
			// then cost this level is equal that:
			// (Total Cost transaction + cost adjustments) divide by transaction quantity
			if (lastCostDetail.getQty().signum() != 0)
			{
				costLowLevel =  lastCostDetail.getCostAmtLL().add(lastCostDetail.getCostAdjustmentLL())
						.divide(lastCostDetail.getQty(), accountSchema.getCostingPrecision(), RoundingMode.HALF_UP)
						.abs();
			}
			// return unit cost from last transaction
			// transaction quantity is zero
			// the cost this level is equal that:
			// (Total Cost Transaction + cost adjustments + accumulate cost) divide between on hand quantity
			else if (lastCostDetail.getCumulatedQty().add( lastCostDetail.getQty()).signum() != 0)
			{
				costLowLevel =  lastCostDetail.getCostAmtLL().add(lastCostDetail.getCostAdjustmentLL()).add(lastCostDetail.getCumulatedAmtLL())
						.divide(
							lastCostDetail.getCumulatedQty().add(lastCostDetail.getQty()),
							accountSchema.getCostingPrecision(),
							RoundingMode.HALF_UP
						)
						.abs();

				return costLowLevel;
			}
			// Return of unit cost from inventory value
			// Cumulated quantity is different of zero
			// then cost this level is equal that:
			// (Total Cost transaction + cost adjustments + Cumulated amount) divide by On hand Quantity
			else if (lastCostDetail.getCumulatedQty().signum() != 0)
			{
				costLowLevel = lastCostDetail.getCumulatedAmtLL()
						.divide(lastCostDetail.getCumulatedQty(), accountSchema.getCostingPrecision(), RoundingMode.HALF_UP)
						.abs();
				return costLowLevel;
			}

		}


        // Preserve a recorded unit estimate after a zero-quantity, zero-value event.
        if (lastCostDetail != null && lastCostDetail.getQty().signum() == 0
                && lastCostDetail.getCumulatedQty().signum() == 0
                && lastCostDetail.getCumulatedAmtLL().add(lastCostDetail.getCostAmtLL())
                        .add(lastCostDetail.getCostAdjustmentLL()).signum() == 0)
            costLowLevel = lastCostDetail.getCurrentCostPriceLL().abs();

		return costLowLevel;
	}

    /**
     * clear Accounting
     * @param accountSchema
     * @param transaction
     */
	public void clearAccounting(MAcctSchema accountSchema, MTransaction transaction) {
        clearAccountingForRecalculation(accountSchema, transaction);
    }

    /** Reset accounting and report whether cost recalculation may proceed. */
	public boolean clearAccountingForRecalculation(MAcctSchema accountSchema , MTransaction transaction) {

            if (transaction.getM_InOutLine_ID() > 0) {
                MInOutLine line = (MInOutLine) transaction.getM_InOutLine();
                List<MMatchPO> orderMatches = MMatchPO.getInOutLine(line);
                List<MMatchInv> invoiceMatches = MMatchInv.getInOutLine(line);
                // Check every related document before removing any facts. Matches
                // may belong to a different accounting period from the receipt.
                if (!canClearAccounting(accountSchema, accountSchema.getM_CostType(), line.getParent(),
                        transaction.getM_Product_ID(), line.getDateAcct()))
                    return false;
                for (MMatchPO match : orderMatches)
                    if (!canClearAccounting(accountSchema, accountSchema.getM_CostType(), match,
                            transaction.getM_Product_ID(), match.getDateAcct())) return false;
                for (MMatchInv match : invoiceMatches)
                    if (!canClearAccounting(accountSchema, accountSchema.getM_CostType(), match,
                            transaction.getM_Product_ID(), match.getDateAcct())) return false;
                clearAccounting(accountSchema, accountSchema.getM_CostType(), line.getParent(),
                        transaction.getM_Product_ID(), line.getDateAcct());
                for (MMatchPO match : orderMatches)
                    clearAccounting(accountSchema, accountSchema.getM_CostType(), match,
                            transaction.getM_Product_ID(), match.getDateAcct());
                for (MMatchInv match : invoiceMatches)
                    clearAccounting(accountSchema, accountSchema.getM_CostType(), match,
                            transaction.getM_Product_ID(), match.getDateAcct());
            }
			else if (transaction.getC_ProjectIssue_ID() > 0) {
				MProjectIssue line = (MProjectIssue) transaction.getC_ProjectIssue();
				if (!clearAccounting(accountSchema, accountSchema.getM_CostType() , line,  transaction.getM_Product_ID() , line.getMovementDate()))
					return false;
			}

			else if (transaction.getM_InventoryLine_ID() > 0) {
				MInventoryLine line = (MInventoryLine) transaction.getM_InventoryLine();
				if (!clearAccounting(accountSchema, accountSchema.getM_CostType() , line.getParent(),  transaction.getM_Product_ID() , line.getDateAcct()))
					return false;
			}
			else if (transaction.getM_MovementLine_ID() > 0) {
				MMovementLine line = (MMovementLine) transaction.getM_MovementLine();
				if (!clearAccounting(accountSchema, accountSchema.getM_CostType() , line.getParent(),  transaction.getM_Product_ID() , line.getDateAcct()))
					return false;
			}
			
			else if (transaction.getM_ProductionLine_ID() > 0) {
				MProductionLine line = (MProductionLine) transaction.getM_ProductionLine();
				MProduction production = (MProduction) line.getM_ProductionPlan().getM_Production();
				if (!clearAccounting(accountSchema, accountSchema.getM_CostType() , production,  transaction.getM_Product_ID()  , production.getMovementDate()))
					return false;

			}
			else if (transaction.getPP_Cost_Collector_ID() > 0)
			{
				X_PP_Cost_Collector costCollector = (X_PP_Cost_Collector) transaction.getPP_Cost_Collector();
				return clearAccounting(accountSchema, accountSchema.getM_CostType(), costCollector, costCollector.getM_Product_ID(), costCollector.getDateAcct());
			}
			else
			{
				log.info("Document does not exist :" + transaction);
                return false;
			}	

        return true;
	}

    /**
     * Clear Accounting
     * @param accountSchema
     * @param costType
     * @param model
	 * @param productId
     * @param dateAcct
     * @return true clean
     */
	private boolean canClearAccounting(MAcctSchema accountSchema, I_M_CostType costType, PO model, int productId , Timestamp dateAcct) {
		// check if costing type need reset accounting 
		if (!accountSchema.getCostingMethod().equals(costType.getCostingMethod())) {
			MProduct product = MProduct.get(accountSchema.getCtx() , productId);
			MProductCategoryAcct productCategoryAcct = MProductCategoryAcct.get(accountSchema.getCtx(), product.getM_Product_Category_ID(), accountSchema.get_ID(), model.get_TrxName());
			if (productCategoryAcct == null || !costType.getCostingMethod().equals(productCategoryAcct.getCostingMethod()))
				return false;
		}
		final String  docBaseType;
		// check if account period is open
		if(model instanceof MMatchInv)
			docBaseType = MPeriodControl.DOCBASETYPE_MatchInvoice;
		else if (model instanceof MMatchPO)
			docBaseType = MPeriodControl.DOCBASETYPE_MatchPO;
		else if (model instanceof MProduction)
			docBaseType = MPeriodControl.DOCBASETYPE_MaterialProduction;
		else
		{		
			MDocType docType = MDocType.get(model.getCtx(), model.get_ValueAsInt(MDocType.COLUMNNAME_C_DocType_ID));
			docBaseType = docType.getDocBaseType();
		}
			
		Boolean openPeriod = MPeriod.isOpen(model.getCtx(), dateAcct , docBaseType ,  model.getAD_Org_ID());
		if (!openPeriod)
		{	
			System.out.println("Period closed.");
			return false;
		}	

        return true;
    }

	public boolean clearAccounting(MAcctSchema accountSchema, I_M_CostType costType, PO model, int productId, Timestamp dateAcct) {
        if (!canClearAccounting(accountSchema, costType, model, productId, dateAcct))
            return false;
		final String sqlUpdate = "UPDATE " + model.get_TableName() + " SET Posted = 'N' WHERE "+ model.get_TableName() + "_ID=?";
		DB.executeUpdateEx(sqlUpdate, new Object[] {model.get_ID()}, model.get_TrxName());
		//Delete account
		final String sqldelete = "DELETE FROM Fact_Acct WHERE Record_ID =? AND AD_Table_ID=?";		
		DB.executeUpdateEx(sqldelete, new Object[] {model.get_ID(), model.get_Table_ID()}, model.get_TrxName());
		return true;
	}
}
