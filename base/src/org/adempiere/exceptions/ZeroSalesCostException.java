package org.adempiere.exceptions;

import java.sql.Timestamp;

/** A physical sales shipment cannot be costed or posted without a nonzero cost. */
public class ZeroSalesCostException extends AdempiereException {
    private static final long serialVersionUID = 4411L;

    public ZeroSalesCostException(int productId, int lineId, int schemaId, int costTypeId, Timestamp dateAcct) {
        super("ZERO_SALES_COST: @M_Product_ID@=" + productId + ", @M_InOutLine_ID@=" + lineId
                + ", @C_AcctSchema_ID@=" + schemaId + ", @M_CostType_ID@=" + costTypeId
                + ", @DateAcct@=" + dateAcct);
    }
}
