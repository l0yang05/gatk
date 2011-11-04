package org.broadinstitute.sting.gatk.walkers.newassociation.features;

import org.broadinstitute.sting.utils.sam.GATKSAMRecord;
import org.broadinstitute.sting.gatk.walkers.newassociation.RFAArgumentCollection;

/**
 * Created by IntelliJ IDEA.
 * User: chartl
 * Date: 9/28/11
 * Time: 11:58 AM
 * To change this template use File | Settings | File Templates.
 */
public class InsertSize extends ReadFeature {

    public InsertSize(RFAArgumentCollection col) {
        super(col);
    }

    public String getName() { return "InsertSize"; }

    public String getKey() { return "insertSize"; }

    public String getDescription() { return "the insert size distribution among all valid reads"; }

    public boolean isDefinedFor(GATKSAMRecord read) {
        return read.getReadPairedFlag() && ! read.getMateUnmappedFlag();
    }

    public Object getFeature(GATKSAMRecord read) {
        return Math.abs(read.getInferredInsertSize());
    }
}
