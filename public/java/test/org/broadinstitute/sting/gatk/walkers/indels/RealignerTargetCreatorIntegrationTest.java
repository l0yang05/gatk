package org.broadinstitute.sting.gatk.walkers.indels;

import org.broadinstitute.sting.WalkerTest;
import org.testng.annotations.Test;

import java.util.Arrays;

public class RealignerTargetCreatorIntegrationTest extends WalkerTest {

    @Test
    public void testIntervals() {

        WalkerTest.WalkerTestSpec spec1 = new WalkerTest.WalkerTestSpec(
                "-T RealignerTargetCreator -R " + b36KGReference + " -I " + validationDataLocation + "NA12878.1kg.p2.chr1_10mb_11_mb.SLX.bam --mismatchFraction 0.15 -L 1:10,000,000-10,050,000 -o %s",
                 1,
                 Arrays.asList("3f0b63a393104d0c4158c7d1538153b8"));
        executeTest("test standard", spec1);

        WalkerTest.WalkerTestSpec spec2 = new WalkerTest.WalkerTestSpec(
                "-T RealignerTargetCreator --known " + b36dbSNP129 + " -R " + b36KGReference + " -I " + validationDataLocation + "NA12878.1kg.p2.chr1_10mb_11_mb.SLX.bam -L 1:10,000,000-10,050,000 -o %s",
                 1,
                 Arrays.asList("5085054c78e256432dc75c85a9ac631c"));
        executeTest("test dbsnp", spec2);

        WalkerTest.WalkerTestSpec spec3 = new WalkerTest.WalkerTestSpec(
                "-T RealignerTargetCreator -R " + b36KGReference + " --known " + validationDataLocation + "NA12878.chr1_10mb_11mb.slx.indels.vcf4 -BTI known -o %s",
                 1,
                 Arrays.asList("5206cee6c01b299417bf2feeb8b3dc96"));
        executeTest("test rods only", spec3);
    }
}
