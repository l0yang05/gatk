/*
 * Copyright (c) 2010 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.gatk.walkers.variantrecalibration;

import org.broad.tribble.dbsnp.DbSNPFeature;
import org.broad.tribble.util.variantcontext.VariantContext;
import org.broadinstitute.sting.commandline.CommandLineUtils;
import org.broadinstitute.sting.commandline.Hidden;
import org.broadinstitute.sting.commandline.Output;
import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.gatk.datasources.simpleDataSources.ReferenceOrderedDataSource;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.gatk.refdata.utils.helpers.DbSNPHelper;
import org.broadinstitute.sting.gatk.walkers.RodWalker;
import org.broadinstitute.sting.utils.collections.ExpandingArrayList;
import org.broadinstitute.sting.commandline.Argument;
import org.broadinstitute.sting.utils.exceptions.UserException;

import java.io.PrintStream;
import java.util.*;

/**
 * Takes variant calls as .vcf files, learns a Gaussian mixture model over the variant annotations producing calibrated variant cluster parameters which can be applied to other datasets
 *
 * @author rpoplin
 * @since Feb 11, 2010
 *
 * @help.summary Takes variant calls as .vcf files, learns a Gaussian mixture model over the variant annotations producing calibrated variant cluster parameters which can be applied to other datasets
 */

public class GenerateVariantClustersWalker extends RodWalker<ExpandingArrayList<VariantDatum>, ExpandingArrayList<VariantDatum>> {

    /////////////////////////////
    // Outputs
    /////////////////////////////

    @Output(fullName="cluster_file", shortName="clusterFile", doc="The output cluster file", required=true)
    private PrintStream CLUSTER_FILE;

    /////////////////////////////
    // Command Line Arguments
    /////////////////////////////
    @Argument(fullName="ignore_all_input_filters", shortName="ignoreAllFilters", doc="If specified the optimizer will use variants even if the FILTER column is marked in the VCF file", required=false)
    private boolean IGNORE_ALL_INPUT_FILTERS = false;
    @Argument(fullName="ignore_filter", shortName="ignoreFilter", doc="If specified the optimizer will use variants even if the specified filter name is marked in the input VCF file", required=false)
    private String[] IGNORE_INPUT_FILTERS = null;
    @Argument(fullName="use_annotation", shortName="an", doc="The names of the annotations which should used for calculations", required=true)
    private String[] USE_ANNOTATIONS = null;
    @Argument(fullName="maxGaussians", shortName="mG", doc="The maximum number of Gaussians to try during Bayesian clustering", required=false)
    private int MAX_GAUSSIANS = 4;
    @Argument(fullName="maxIterations", shortName="mI", doc="The maximum number of iterations to be performed when clustering. Clustering will normally end when convergence is detected.", required=false)
    private int MAX_ITERATIONS = 200;
    @Argument(fullName="weightNovel", shortName="weightNovel", doc="The weight for novel variants during clustering", required=false)
    private double WEIGHT_NOVELS = 0.0;
    @Argument(fullName="weightDBSNP", shortName="weightDBSNP", doc="The weight for dbSNP variants during clustering", required=false)
    private double WEIGHT_DBSNP = 0.0;
    @Argument(fullName="weightHapMap", shortName="weightHapMap", doc="The weight for HapMap variants during clustering", required=false)
    private double WEIGHT_HAPMAP = 1.0;
    @Argument(fullName="weight1KG", shortName="weight1KG", doc="The weight for 1000 Genomes Project variants during clustering", required=false)
    private double WEIGHT_1KG = 1.0;
    @Argument(fullName="forceIndependent", shortName="forceIndependent", doc="Force off-diagonal entries in the covariance matrix to be zero.", required=false)
    private boolean FORCE_INDEPENDENT = false;
    @Argument(fullName="stdThreshold", shortName="std", doc="If a variant has annotations more than -std standard deviations away from mean then don't use it for clustering.", required=false)
    private double STD_THRESHOLD = 4.5;
    @Argument(fullName="qualThreshold", shortName="qual", doc="If a known variant has raw QUAL value less than -qual then don't use it for clustering.", required=false)
    private double QUAL_THRESHOLD = 800.0;
    @Argument(fullName="shrinkage", shortName="shrinkage", doc="The shrinkage parameter in variational Bayes algorithm.", required=false)
    private double SHRINKAGE = 0.0001;
    @Argument(fullName="dirichlet", shortName="dirichlet", doc="The dirichlet parameter in variational Bayes algoirthm.", required=false)
    private double DIRICHLET_PARAMETER = 1000.0;

    /////////////////////////////
    // Debug Arguments
    /////////////////////////////
    @Hidden
    @Argument(fullName = "NO_HEADER", shortName = "NO_HEADER", doc = "Don't output the usual VCF header tag with the command line. FOR DEBUGGING PURPOSES ONLY. This option is required in order to pass integration tests.", required = false)
    protected Boolean NO_HEADER_LINE = false;
    @Hidden
    @Argument(fullName = "NoByHapMapValidationStatus", shortName = "NoByHapMapValidationStatus", doc = "Don't consider sites in dbsnp rod tagged as by-hapmap validation status as real HapMap sites. FOR DEBUGGING PURPOSES ONLY.", required=false)
    private Boolean NO_BY_HAPMAP_VALIDATION_STATUS = false;

    /////////////////////////////
    // Private Member Variables
    /////////////////////////////
    private ExpandingArrayList<String> annotationKeys;
    private Set<String> ignoreInputFilterSet = null;
    private Set<String> inputNames = new HashSet<String>();
    private VariantOptimizationModel.Model OPTIMIZATION_MODEL = VariantOptimizationModel.Model.GAUSSIAN_MIXTURE_MODEL;
    private VariantGaussianMixtureModel theModel = new VariantGaussianMixtureModel();

    //---------------------------------------------------------------------------------------------------------------
    //
    // initialize
    //
    //---------------------------------------------------------------------------------------------------------------

    public void initialize() {
        //if( !PATH_TO_RESOURCES.endsWith("/") ) { PATH_TO_RESOURCES = PATH_TO_RESOURCES + "/"; }
        
        annotationKeys = new ExpandingArrayList<String>(Arrays.asList(USE_ANNOTATIONS));

        if( IGNORE_INPUT_FILTERS != null ) {
            ignoreInputFilterSet = new TreeSet<String>(Arrays.asList(IGNORE_INPUT_FILTERS));
        }

        if( !NO_HEADER_LINE ) {
            CLUSTER_FILE.print("##GenerateVariantClusters = ");
            CLUSTER_FILE.println("\"" + getToolkit().createApproximateCommandLineArgumentString(getToolkit(), this) + "\"");
        }

        boolean foundDBSNP = false;
        for( ReferenceOrderedDataSource d : this.getToolkit().getRodDataSources() ) {
            if( d.getName().startsWith("input") ) {
                inputNames.add(d.getName());
                logger.info("Found input variant track with name " + d.getName());
            } else if ( d.getName().equals(DbSNPHelper.STANDARD_DBSNP_TRACK_NAME) ) {
                logger.info("Found dbSNP track for use in training with weight = " + WEIGHT_DBSNP);
                if( !NO_BY_HAPMAP_VALIDATION_STATUS ) {                                
                    logger.info("\tsites in dbSNP track tagged with by-hapmap validation status will be used in training with weight = " + WEIGHT_HAPMAP);
                }
                foundDBSNP = true;
            } else if ( d.getName().equals("hapmap") ) {
                logger.info("Found HapMap track for use in training with weight = " + WEIGHT_HAPMAP);
            } else if ( d.getName().equals("1kg") ) {
                logger.info("Found 1KG track for use in training with weight = " + WEIGHT_1KG);
            } else {
                logger.info("Not evaluating ROD binding " + d.getName());
            }
        }

        if(!foundDBSNP) {
            throw new UserException.CommandLineException("dbSNP track is required. This calculation is critically dependent on being able to distinguish known and novel sites.");
        }
    }

    //---------------------------------------------------------------------------------------------------------------
    //
    // map
    //
    //---------------------------------------------------------------------------------------------------------------

    public ExpandingArrayList<VariantDatum> map( RefMetaDataTracker tracker, ReferenceContext ref, AlignmentContext context ) {

        final ExpandingArrayList<VariantDatum> mapList = new ExpandingArrayList<VariantDatum>();

        if( tracker == null ) { // For some reason RodWalkers get map calls with null trackers
            return mapList;
        }

        final double annotationValues[] = new double[annotationKeys.size()];

        for( final VariantContext vc : tracker.getVariantContexts(ref, inputNames, null, context.getLocation(), false, false) ) {
            if( vc != null && vc.isSNP() ) {
                if( !vc.isFiltered() || IGNORE_ALL_INPUT_FILTERS || (ignoreInputFilterSet != null && ignoreInputFilterSet.containsAll(vc.getFilters())) ) {
                    int iii = 0;
                    for( final String key : annotationKeys ) {
                        annotationValues[iii++] = theModel.decodeAnnotation( getToolkit().getGenomeLocParser(), key, vc, true );
                    }

                    final VariantDatum variantDatum = new VariantDatum();
                    variantDatum.annotations = annotationValues;
                    variantDatum.qual = vc.getPhredScaledQual();

                    final DbSNPFeature dbsnp = DbSNPHelper.getFirstRealSNP(tracker.getReferenceMetaData(DbSNPHelper.STANDARD_DBSNP_TRACK_NAME));
                    final Collection<VariantContext> vcsHapMap = tracker.getVariantContexts(ref, "hapmap", null, context.getLocation(), false, true);
                    final Collection<VariantContext> vcs1KG = tracker.getVariantContexts(ref, "1kg", null, context.getLocation(), false, true);
                    final VariantContext vcHapMap = ( vcsHapMap.size() != 0 ? vcsHapMap.iterator().next() : null );
                    final VariantContext vc1KG = ( vcs1KG.size() != 0 ? vcs1KG.iterator().next() : null );

                    variantDatum.isKnown = ( dbsnp != null );
                    variantDatum.weight = WEIGHT_NOVELS;                   
                    if( vcHapMap != null || ( !NO_BY_HAPMAP_VALIDATION_STATUS && dbsnp != null && DbSNPHelper.isHapmap(dbsnp) ) ) {
                        variantDatum.weight = WEIGHT_HAPMAP;
                    } else if( vc1KG != null ) {
                        variantDatum.weight = WEIGHT_1KG;
                    } else if( dbsnp != null ) {
                        variantDatum.weight = WEIGHT_DBSNP;
                    }

                    if( variantDatum.weight > 0.0 && variantDatum.qual > QUAL_THRESHOLD ) {
                        mapList.add( variantDatum );
                    }
                }
            }
        }

        return mapList;
    }

    //---------------------------------------------------------------------------------------------------------------
    //
    // reduce
    //
    //---------------------------------------------------------------------------------------------------------------

    public ExpandingArrayList<VariantDatum> reduceInit() {
        return new ExpandingArrayList<VariantDatum>();
    }

    public ExpandingArrayList<VariantDatum> reduce( final ExpandingArrayList<VariantDatum> mapValue, final ExpandingArrayList<VariantDatum> reduceSum ) {
        reduceSum.addAll( mapValue );

        return reduceSum;
    }

    public void onTraversalDone( ExpandingArrayList<VariantDatum> reduceSum ) {

        logger.info( "There are " + reduceSum.size() + " variants with > 0 clustering weight and qual > threshold (--qualThreshold = " + QUAL_THRESHOLD + ")" );
        logger.info( "The annotations used for clustering are: " + annotationKeys );

        final VariantDataManager dataManager = new VariantDataManager( reduceSum, annotationKeys );
        reduceSum.clear(); // Don't need this ever again, clean up some memory

        dataManager.normalizeData(); // Each data point is now [ (x - mean) / standard deviation ]

        // Create either the Gaussian Mixture Model or the Nearest Neighbors model and run it
        switch (OPTIMIZATION_MODEL) {
            case GAUSSIAN_MIXTURE_MODEL:
                theModel = new VariantGaussianMixtureModel( dataManager, MAX_GAUSSIANS, MAX_ITERATIONS, FORCE_INDEPENDENT,
                                                            STD_THRESHOLD, SHRINKAGE, DIRICHLET_PARAMETER );
                break;
            //case K_NEAREST_NEIGHBORS:
            //    theModel = new VariantNearestNeighborsModel( dataManager, TARGET_TITV, NUM_KNN );
            //    break;
            default:
                throw new UserException.BadArgumentValue("OPTIMIZATION_MODEL", "Variant Optimization Model is unrecognized. Implemented options are GAUSSIAN_MIXTURE_MODEL and K_NEAREST_NEIGHBORS" );
        }

        theModel.run( CLUSTER_FILE );
    }
}
