/**
 * Created with IntelliJ IDEA.
 * User: thibault
 * Date: 7/6/12
 * Time: 3:12 PM
 * To change this template use File | Settings | File Templates.
 */

package org.broadinstitute.sting.queue.qscripts.performance

import org.broadinstitute.sting.queue.QScript
import org.broadinstitute.sting.queue.extensions.gatk.{CountLoci, CommandLineGATK, UnifiedGenotyper}
import org.broadinstitute.sting.gatk.walkers.genotyper.GenotypeLikelihoodsCalculationModel.Model

class UGManyBAMsPerformance extends QScript {
  @Argument(shortName = "interval", doc="The interval to test over", required=false)
  val interval: String = "1:1104385-1684501"

  @Argument(shortName = "allBAMs", doc="The source list of BAMs to test", required=false)
  val bamSrcFile: String = "/humgen/gsa-firehose2/ReduceReads_v2_old/ReduceReads.out.bam.list"

  @Argument(shortName = "bamCounts", doc="The list of the counts of BAMs to test", required=false)
  val bamCounts: List[Int] = List(1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096)

  @Argument(shortName = "memVal", doc="The list of given RAM values (in GB) to test", required=false)
  val memoryValues: List[Int] = List(1, 2, 4, 8, 16, 32)

  val referenceFile = new File("/seq/references/Homo_sapiens_assembly19/v1/Homo_sapiens_assembly19.fasta")
  val dbsnpFile = new File("/humgen/gsa-pipeline/resources/b37/v4/dbsnp_135.b37.vcf")

  trait UNIVERSAL_GATK_ARGS extends CommandLineGATK {
    this.logging_level = "INFO"
    this.reference_sequence = referenceFile
    this.intervalsString = Seq(interval)
    this.downsample_to_coverage = 60
  }

  trait UG_ARGS extends UnifiedGenotyper with UNIVERSAL_GATK_ARGS {
    this.genotype_likelihoods_model = Model.BOTH
    this.capMaxAllelesForIndels = true
    this.dbsnp = dbsnpFile
  }

  class SliceList(n: Int, @Input bamList: File) extends CommandLineFunction {
    this.analysisName = "SliceList"
    @Output(doc="foo") var list: File = new File("bams.%d.list".format(n))
    def commandLine = "head -n %d %s | awk '{print \"\" $1}' > %s".format(n, bamList, list)
  }

  def script() {
    for (numBAMs <- bamCounts) {
      val sublist = new SliceList(numBAMs, bamSrcFile)
      add(sublist)

      for (givenMem <- memoryValues) {
        val cl = new CountLoci() with UNIVERSAL_GATK_ARGS
        val clOutFile = "Count_%d_BAMs_%d_GB.txt".format(numBAMs, givenMem)
        cl.out = new File(clOutFile)
        cl.memoryLimit = givenMem
        cl.input_file :+= sublist.list

        cl.configureJobReport(Map(
          "numBAMs" -> numBAMs,
          "givenMem" -> givenMem))
        add(cl)

        val ug = new UnifiedGenotyper() with UG_ARGS
        val ugOutFile = "Performance_%d_BAMs_%d_GB.vcf".format(numBAMs, givenMem)
        ug.out = new File(ugOutFile)
        ug.memoryLimit = givenMem
        ug.input_file :+= sublist.list

        ug.configureJobReport(Map(
          "numBAMs" -> numBAMs,
          "givenMem" -> givenMem))
        add(ug)
      }
    }
  }
}
